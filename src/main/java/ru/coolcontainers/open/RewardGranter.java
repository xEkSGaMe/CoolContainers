package ru.coolcontainers.open;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.api.enums.OpeningStatus;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.cases.dto.RewardSnapshot;
import ru.coolcontainers.db.Database;
import ru.coolcontainers.db.DbExecutor;
import ru.coolcontainers.db.TransactionHelper;
import ru.coolcontainers.db.repository.GrantRepository;
import ru.coolcontainers.db.repository.OpeningRepository;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Идемпотентная выдача награды. Все SQL — в DbExecutor, side-effects — на main.
 *
 * Шаги:
 *  1) async: транзакция X→GRANTING + persist snapshot + INSERT PROCESSING.
 *  2) side-effect: LP — async (синхронный вызов), остальное — main.
 *  3) async: grant → GRANTED / FAILED.
 *  4) async: opening → GRANTED.
 *
 * При провале side-effect opening переводится в FAILED, чтобы не зацикливаться
 * в recovery. Админ восстанавливает через /caseadmin recover refund (см.
 * OpeningRepository.forceRefunded).
 */
public final class RewardGranter {

    /**
     * Runtime-путь: обычная анимация. Opening только что создан в ANIMATING
     * (см. CaseOpenService.startOpenAsync), поэтому этот единственный статус
     * и допустим для автоматической выдачи.
     */
    private static final Set<OpeningStatus> RUNTIME_FROM =
            EnumSet.of(OpeningStatus.ANIMATING);

    /**
     * Admin-путь: /caseadmin recover X grant.
     *
     * P0: GRANTING ЗАПРЕЩЁН в качестве источника.
     *
     * Почему это критично: если разрешить GRANTING→GRANTING, то side-effect
     * (LP/Vault/PP/items/commands) выполнится ПОВТОРНО на opening'е, где он
     * уже мог сработать в прошлый раз. Это прямой дюп.
     *
     * Если side-effect действительно уже сработал (grant=GRANTED) — это
     * защищено в /caseadmin recover refund. Если side-effect НЕ сработал
     * (grant=PROCESSING/FAILED) — это P1-сценарий потери награды, его надо
     * решать через recover refund + forceRefunded вручную, но не через дюп.
     *
     * Из FAILED/REFUNDED/GRANTED выдача через admin тоже запрещена.
     */
    private static final Set<OpeningStatus> ADMIN_FROM =
            EnumSet.of(OpeningStatus.PENDING,
                    OpeningStatus.ANIMATING);

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExecutor;
    private final OpeningRepository openingRepo;
    private final GrantRepository grantRepo;
    private final RewardHooks hooks;
    private final String serverId;

    public RewardGranter(@NotNull JavaPlugin plugin,
                         @NotNull Database db,
                         @NotNull DbExecutor dbExecutor,
                         @NotNull OpeningRepository openingRepo,
                         @NotNull GrantRepository grantRepo,
                         @NotNull RewardHooks hooks,
                         @NotNull String serverId) {
        this.plugin = plugin;
        this.db = db;
        this.dbExecutor = dbExecutor;
        this.openingRepo = openingRepo;
        this.grantRepo = grantRepo;
        this.hooks = hooks;
        this.serverId = serverId;
    }

    public @NotNull CompletableFuture<Boolean> grantAsync(@NotNull UUID openingId,
                                                          @NotNull Player player,
                                                          @NotNull CaseDefinition def,
                                                          @NotNull Reward reward,
                                                          @NotNull RewardSnapshot snapshot) {
        return grantInternal(openingId, player, def, reward, snapshot, RUNTIME_FROM);
    }

    public @NotNull CompletableFuture<Boolean> grantAdminAsync(@NotNull UUID openingId,
                                                               @NotNull Player player,
                                                               @NotNull CaseDefinition def,
                                                               @NotNull Reward reward,
                                                               @NotNull RewardSnapshot snapshot) {
        return grantInternal(openingId, player, def, reward, snapshot, ADMIN_FROM);
    }

    private CompletableFuture<Boolean> grantInternal(@NotNull UUID openingId,
                                                     @NotNull Player player,
                                                     @NotNull CaseDefinition def,
                                                     @NotNull Reward reward,
                                                     @NotNull RewardSnapshot snapshot,
                                                     @NotNull Set<OpeningStatus> allowedFrom) {
        // --- Шаг 1: durable GRANTING + persist snapshot + PROCESSING ---
        CompletableFuture<Boolean> step1 = dbExecutor.supply(() ->
                TransactionHelper.inTransaction(db, c -> {
                    if (!openingRepo.updateStatusFromAny(
                            c, openingId, allowedFrom, OpeningStatus.GRANTING)) {
                        return false;
                    }
                    // P2-3: сохраняем snapshot ДО side-effect, в той же транзакции.
                    // При падении сервера между этим коммитом и side-effect'ом
                    // /caseadmin opening покажет reward_snapshot — админ поймёт,
                    // что именно выпало игроку, и примет решение.
                    openingRepo.setRewardSnapshotTx(c, openingId, snapshot);
                    grantRepo.insertProcessing(c, openingId, reward.getId(), snapshot, serverId);
                    return true;
                })
        );

        return step1.thenCompose(marked -> {
            if (!Boolean.TRUE.equals(marked)) {
                plugin.getLogger().warning("grant: opening " + openingId
                        + " не в одном из " + allowedFrom + " — пропускаем");
                return CompletableFuture.completedFuture(false);
            }

            // --- Шаг 2: side-effect (LP async, остальное main) ---
            CompletableFuture<Throwable> sideEffect = executeSideEffectAsync(player, reward);

            // --- Шаг 3+4: финализация ---
            return sideEffect.thenCompose(err -> {
                if (err != null) {
                    String msg = err.getClass().getSimpleName() + ": " + err.getMessage();
                    plugin.getLogger().warning("grant: side-effect упал для " + openingId
                            + " reward=" + reward.getId() + ": " + msg);
                    return dbExecutor.supply(() -> {
                        try {
                            grantRepo.markFailed(openingId, reward.getId(), msg);
                        } catch (SQLException e) {
                            plugin.getLogger().warning("grant: markFailed grant: " + e.getMessage());
                        }
                        try {
                            openingRepo.markFailed(openingId);
                        } catch (SQLException e) {
                            plugin.getLogger().warning("grant: markFailed opening: " + e.getMessage());
                        }
                        return false;
                    });
                }

                return dbExecutor.supply(() -> {
                    try {
                        grantRepo.markGranted(openingId, reward.getId());
                    } catch (SQLException e) {
                        plugin.getLogger().warning("grant: markGranted grant: " + e.getMessage());
                    }
                    boolean ok = false;
                    try {
                        ok = openingRepo.markGranted(openingId, snapshot);
                        if (!ok) {
                            plugin.getLogger().warning("grant: opening " + openingId
                                    + " не переведён в GRANTED (терминальный?). "
                                    + "Side-effect уже сработал — проверь вручную.");
                            ok = true;
                        }
                    } catch (SQLException e) {
                        plugin.getLogger().warning("grant: markGranted opening: " + e.getMessage());
                    }
                    return ok;
                });
            });
        });
    }

    /**
     * Side-effect в две стадии:
     *
     *  1. LP — на async-потоке (Bukkit scheduler), потому что LuckPermsHook.grant
     *     теперь синхронный (ждёт saveUser). На main нельзя — заблокирует сервер.
     *  2. Vault/PP/items/commands — на main (требуют main-поток).
     *
     * Возвращает CompletableFuture<Throwable>: null при успехе, Throwable при
     * ошибке любого шага. Это гарантирует, что opening не уйдёт в GRANTED,
     * если LP-нода фактически не сохранилась (P1-5).
     */
    private CompletableFuture<Throwable> executeSideEffectAsync(@NotNull Player player,
                                                                @NotNull Reward reward) {
        CompletableFuture<Throwable> result = new CompletableFuture<>();

        if (reward.getLuckPerms() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    hooks.giveLuckPerms(player.getUniqueId(), reward.getLuckPerms());
                    runMainSideEffects(player, reward, result);
                } catch (Throwable t) {
                    result.complete(t);
                }
            });
        } else {
            runMainSideEffects(player, reward, result);
        }

        return result;
    }

    private void runMainSideEffects(@NotNull Player player,
                                    @NotNull Reward reward,
                                    @NotNull CompletableFuture<Throwable> result) {
        dbExecutor.onMain(() -> {
            try {
                if (reward.getVault() != null) {
                    hooks.giveVault(player, reward.getVault().amount());
                }
                if (reward.getPlayerPoints() != null) {
                    hooks.givePlayerPoints(player.getUniqueId(),
                            reward.getPlayerPoints().amount());
                }
                if (!reward.getItems().isEmpty()) {
                    hooks.giveItems(player, reward.getItems());
                }
                if (!reward.getCommands().isEmpty()) {
                    hooks.runCommands(player, reward.getCommands());
                }
                result.complete(null);
            } catch (Throwable t) {
                result.complete(t);
            }
        });
    }
}