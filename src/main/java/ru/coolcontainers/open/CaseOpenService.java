package ru.coolcontainers.open;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.api.enums.OpeningStatus;
import ru.coolcontainers.api.events.CaseKeyChangeEvent;
import ru.coolcontainers.api.events.CaseOpenPreEvent;
import ru.coolcontainers.api.events.CaseOpenStartEvent;
import ru.coolcontainers.cases.CaseRegistry;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.db.Database;
import ru.coolcontainers.db.DbExecutor;
import ru.coolcontainers.db.TransactionHelper;
import ru.coolcontainers.db.repository.KeyRepository;
import ru.coolcontainers.db.repository.OpeningRepository;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Транзакционное открытие кейса. Все SQL — в DbExecutor.
 *
 * Шаги (внутри одной транзакции):
 *   1) atomicSpend(1)
 *   2) INSERT opening PENDING
 *   3) log transaction -1 OPEN
 *   4) PENDING → ANIMATING
 *
 * События:
 *   CaseOpenPreEvent  — на main до транзакции.
 *   CaseOpenStartEvent — на main после успешной транзакции.
 *   CaseKeyChangeEvent — на main после траты ключа (обновляет KeyCache).
 *
 * @return future с UUID opening'а, либо null (PreEvent cancelled, нет ключа, БД упала).
 */
public final class CaseOpenService {

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExecutor;
    private final CaseRegistry registry;
    private final KeyRepository keyRepo;
    private final OpeningRepository openingRepo;
    private final String serverId;

    public CaseOpenService(@NotNull JavaPlugin plugin,
                           @NotNull Database db,
                           @NotNull DbExecutor dbExecutor,
                           @NotNull CaseRegistry registry,
                           @NotNull KeyRepository keyRepo,
                           @NotNull OpeningRepository openingRepo,
                           @NotNull String serverId) {
        this.plugin = plugin;
        this.db = db;
        this.dbExecutor = dbExecutor;
        this.registry = registry;
        this.keyRepo = keyRepo;
        this.openingRepo = openingRepo;
        this.serverId = serverId;
    }

    public @NotNull CompletableFuture<UUID> startOpenAsync(@NotNull Player player,
                                                           @NotNull String caseId,
                                                           @NotNull Location block) {
        // Обязательно на main.
        CaseDefinition def = registry.get(caseId);
        if (def == null) return CompletableFuture.completedFuture(null);

        CaseOpenPreEvent preEvent = new CaseOpenPreEvent(
                player,
                caseId,
                block.getWorld().getName(),
                block.getBlockX(), block.getBlockY(), block.getBlockZ()
        );
        Bukkit.getPluginManager().callEvent(preEvent);
        if (preEvent.isCancelled()) return CompletableFuture.completedFuture(null);

        UUID openingId = UUID.randomUUID();
        UUID playerUuid = player.getUniqueId();

        final int[] newAmountHolder = {-1};

        return dbExecutor.supply(() ->
                TransactionHelper.inTransaction(db, c -> {
                    if (!keyRepo.atomicSpend(c, playerUuid, caseId, 1)) {
                        return false;
                    }
                    openingRepo.insertPending(c, openingId, playerUuid, caseId, 1, serverId);
                    keyRepo.logTransactionTx(c, playerUuid, caseId, -1,
                            KeyReason.OPEN, null, serverId);
                    if (!openingRepo.updateStatusTx(c, openingId,
                            OpeningStatus.PENDING, OpeningStatus.ANIMATING)) {
                        throw new SQLException("PENDING→ANIMATING failed для " + openingId);
                    }
                    newAmountHolder[0] = keyRepo.getAmountTx(c, playerUuid, caseId);
                    return true;
                })
        ).thenApply(ok -> {
            if (!Boolean.TRUE.equals(ok)) return null;

            final int newAmount = newAmountHolder[0];
            dbExecutor.onMain(() -> {
                Bukkit.getPluginManager().callEvent(
                        new CaseOpenStartEvent(openingId, player, caseId));

                if (newAmount >= 0) {
                    Bukkit.getPluginManager().callEvent(new CaseKeyChangeEvent(
                            playerUuid, caseId, -1, newAmount, KeyReason.OPEN, null));
                }
            });
            return openingId;
        }).exceptionally(ex -> {
            plugin.getLogger().warning("startOpenAsync: " + ex.getMessage());
            return null;
        });
    }
}