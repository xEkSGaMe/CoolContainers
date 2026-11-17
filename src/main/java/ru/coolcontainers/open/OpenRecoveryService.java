package ru.coolcontainers.open;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.enums.GrantStatus;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.api.events.CaseOpenCancelEvent;
import ru.coolcontainers.cases.CaseRegistry;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.cases.dto.RewardSnapshot;
import ru.coolcontainers.db.Database;
import ru.coolcontainers.db.DbExecutor;
import ru.coolcontainers.db.repository.GrantRepository;
import ru.coolcontainers.db.repository.OpeningRepository;
import ru.coolcontainers.key.KeyService;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Recovery после краха. Вызывается из onEnable; работает на отдельном async-потоке.
 * Все Bukkit API (getPlayer, callEvent) — через dbExecutor.onMain(...).
 *
 * P1-6: возврат ключа идёт через KeyService, а не через KeyRepository — чтобы
 * сработал CaseKeyChangeEvent (KeyCache, KeyLineHologramService).
 *
 * P2-3: если reward_snapshot сохранён — используем его (не роллим заново).
 * Это критично, потому что игрок уже видел результат анимации; роллить
 * заново = выдать другую награду.
 */
public final class OpenRecoveryService {

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExecutor;
    private final CaseRegistry registry;
    private final KeyService keyService;
    private final OpeningRepository openingRepo;
    private final GrantRepository grantRepo;
    private final RewardRoller roller;
    private final RewardGranter granter;
    private final String serverId;

    public OpenRecoveryService(@NotNull JavaPlugin plugin,
                               @NotNull Database db,
                               @NotNull DbExecutor dbExecutor,
                               @NotNull CaseRegistry registry,
                               @NotNull KeyService keyService,
                               @NotNull OpeningRepository openingRepo,
                               @NotNull GrantRepository grantRepo,
                               @NotNull RewardRoller roller,
                               @NotNull RewardGranter granter,
                               @NotNull String serverId) {
        this.plugin = plugin;
        this.db = db;
        this.dbExecutor = dbExecutor;
        this.registry = registry;
        this.keyService = keyService;
        this.openingRepo = openingRepo;
        this.grantRepo = grantRepo;
        this.roller = roller;
        this.granter = granter;
        this.serverId = serverId;
    }

    public void recover() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::recoverAsync);
    }

    private void recoverAsync() {
        List<OpeningRepository.OpeningRow> active;
        try {
            active = openingRepo.findActiveByServer(serverId);
        } catch (SQLException e) {
            plugin.getLogger().severe("recovery: findActiveByServer: " + e.getMessage());
            return;
        }

        if (active.isEmpty()) {
            plugin.getLogger().info("recovery: активных opening'ов нет.");
            return;
        }

        plugin.getLogger().info("recovery: найдено " + active.size()
                + " активных opening'ов на server_id=" + serverId);

        for (OpeningRepository.OpeningRow row : active) {
            try {
                handle(row);
            } catch (Exception e) {
                plugin.getLogger().warning("recovery: ошибка обработки "
                        + row.openingId() + ": " + e.getMessage());
            }
        }
    }

    private void handle(@NotNull OpeningRepository.OpeningRow row) throws Exception {
        Player player = fetchPlayer(row.playerUuid());

        switch (row.status()) {
            case PENDING, ANIMATING -> {
                if (player != null && player.isOnline()) {
                    grantNow(row, player);
                } else {
                    refund(row, KeyReason.REFUND_RECOVERY);
                }
            }
            case GRANTING -> handleGranting(row, player);
            default -> { /* терминальные не трогаем */ }
        }
    }

    private void grantNow(@NotNull OpeningRepository.OpeningRow row,
                          @NotNull Player player) throws Exception {
        CaseDefinition def = registry.get(row.caseId());
        if (def == null) {
            plugin.getLogger().warning("recovery: кейс '" + row.caseId()
                    + "' отсутствует — refund opening " + row.openingId());
            refund(row, KeyReason.REFUND_RECOVERY);
            return;
        }

        Resolved resolved = resolveReward(def, row.rewardSnapshot());
        if (resolved == null) {
            // rewardId из snapshot не найден в текущей версии кейса — обычно
            // значит, что админ отредактировал награды. Роллим заново, чтобы
            // игрок хоть что-то получил (см. warn в resolveReward).
            resolved = new Resolved(roller.roll(def), null);
            resolved = new Resolved(resolved.reward,
                    new RewardSnapshot(resolved.reward.getId(), resolved.reward.getAmount()));
        }

        boolean ok = granter.grantAsync(row.openingId(), player, def,
                resolved.reward, resolved.snapshot).join();
        plugin.getLogger().info("recovery: opening " + row.openingId()
                + " (case=" + row.caseId() + ", player=" + player.getName()
                + ") → " + (ok ? "GRANTED" : "FAILED"));
    }

    private record Resolved(@NotNull Reward reward, @NotNull RewardSnapshot snapshot) {}

    /**
     * Если snapshot != null и rewardId существует в текущей версии кейса —
     * используем его (стабильность). Иначе null (caller роллит заново).
     */
    private @Nullable Resolved resolveReward(@NotNull CaseDefinition def,
                                             @Nullable RewardSnapshot snapshot) {
        if (snapshot == null) return null;
        for (Reward r : def.getRewards()) {
            if (r.getId().equals(snapshot.rewardId())) {
                return new Resolved(r, snapshot);
            }
        }
        plugin.getLogger().warning("recovery: reward '" + snapshot.rewardId()
                + "' не найден в кейсе '" + def.getId()
                + "' (был отредактирован?) — роллю заново");
        return null;
    }

    private void handleGranting(@NotNull OpeningRepository.OpeningRow row,
                                Player player) throws Exception {
        List<GrantRepository.GrantRow> grants = grantRepo.findByOpening(row.openingId());

        if (grants.isEmpty()) {
            if (player != null && player.isOnline()) {
                grantNow(row, player);
            } else {
                refund(row, KeyReason.REFUND_RECOVERY);
            }
            return;
        }

        boolean hasProcessing = grants.stream()
                .anyMatch(g -> g.status() == GrantStatus.PROCESSING);
        boolean hasGranted = grants.stream()
                .anyMatch(g -> g.status() == GrantStatus.GRANTED);

        if (hasProcessing) {
            grantRepo.markAllProcessingFailed(row.openingId(),
                    "recovery: PROCESSING после краша — ручное решение");
            openingRepo.markFailed(row.openingId());
            plugin.getLogger().warning("recovery: opening " + row.openingId()
                    + " (case=" + row.caseId() + ") → FAILED (PROCESSING). "
                    + "Ручное решение: /caseadmin recover " + row.openingId() + " grant|refund");
            return;
        }

        if (hasGranted) {
            RewardSnapshot snap = grants.stream()
                    .filter(g -> g.status() == GrantStatus.GRANTED)
                    .map(GrantRepository.GrantRow::snapshot)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (snap != null) {
                openingRepo.markGranted(row.openingId(), snap);
                plugin.getLogger().info("recovery: opening " + row.openingId()
                        + " → GRANTED (по данным grant'а)");
            } else {
                openingRepo.markFailed(row.openingId());
                plugin.getLogger().warning("recovery: opening " + row.openingId()
                        + " → FAILED (grant GRANTED без snapshot)");
            }
            return;
        }

        openingRepo.markFailed(row.openingId());
        plugin.getLogger().warning("recovery: opening " + row.openingId()
                + " → FAILED (все grants FAILED). Ручное решение: /caseadmin recover "
                + row.openingId() + " refund");
    }

    /**
     * Возврат ключа через KeyService (а не репозиторий напрямую), чтобы
     * сработал CaseKeyChangeEvent и KeyCache не разъехался с БД.
     *
     * Порядок markRefunded → addKeys обязателен: защита от дюпа ключа.
     */
    private void refund(@NotNull OpeningRepository.OpeningRow row,
                        @NotNull KeyReason reason) throws SQLException {
        boolean marked = openingRepo.markRefunded(row.openingId());
        if (!marked) {
            plugin.getLogger().info("recovery: opening " + row.openingId()
                    + " уже терминальный — refund пропущен.");
            return;
        }

        keyService.addKeys(row.playerUuid(), row.caseId(),
                row.keysSpent(), reason, null);

        Player player = fetchPlayer(row.playerUuid());
        if (player != null && player.isOnline()) {
            dbExecutor.onMain(() -> Bukkit.getPluginManager().callEvent(
                    new CaseOpenCancelEvent(row.openingId(), player, row.caseId(), reason)));
        }

        plugin.getLogger().info("recovery: opening " + row.openingId()
                + " → REFUNDED (" + reason + "), ключей возвращено: " + row.keysSpent());
    }

    /** Синхронно получает Player с main-потока. Возвращает null по таймауту. */
    private Player fetchPlayer(@NotNull UUID uuid) {
        CompletableFuture<Player> f = new CompletableFuture<>();
        dbExecutor.onMain(() -> f.complete(Bukkit.getPlayer(uuid)));
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}