package ru.coolcontainers.key;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.api.events.CaseKeyChangeEvent;
import ru.coolcontainers.db.Database;
import ru.coolcontainers.db.TransactionHelper;
import ru.coolcontainers.db.repository.KeyRepository;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Операции над виртуальными ключами.
 * Атомарность обеспечивается KeyRepository#atomicSpend (single UPDATE).
 * Все изменения пишутся в cc_key_transactions и дают CaseKeyChangeEvent.
 *
 * P2-6: в отличие от прошлой версии, счетчик и запись в cc_key_transactions
 * теперь в ОДНОЙ транзакции. Раньше addKeys/takeKeys:
 *   1) keyRepo.addKeys (отдельный коннект)
 *   2) keyRepo.logTransaction (отдельный коннект)
 *   3) fireKeyChange (после)
 * Если шаг 2 падал — ключ уже начислен/списан, но ни записи, ни события.
 * Кэш расходился с БД без единого способа это заметить.
 *
 * Теперь fireKeyChange вызывается ТОЛЬКО после успешного commit'а обоих шагов.
 */
public final class KeyService {

    private final JavaPlugin plugin;
    private final Database db;
    private final KeyRepository keyRepo;
    private final String serverId;

    public KeyService(@NotNull JavaPlugin plugin,
                      @NotNull Database db,
                      @NotNull KeyRepository keyRepo,
                      @NotNull String serverId) {
        this.plugin = plugin;
        this.db = db;
        this.keyRepo = keyRepo;
        this.serverId = serverId;
    }

    public int getAmount(@NotNull UUID player, @NotNull String caseId) throws SQLException {
        return keyRepo.getAmount(player, caseId);
    }

    /**
     * Начисление ключей (ADMIN_GIVE, REFUND_*).
     * Возвращает новое количество.
     */
    public int addKeys(@NotNull UUID player,
                       @NotNull String caseId,
                       int amount,
                       @NotNull KeyReason reason,
                       @Nullable UUID adminUuid) throws SQLException {
        if (amount <= 0) return getAmount(player, caseId);

        final int[] newAmountHolder = {-1};
        TransactionHelper.inTransaction(db, c -> {
            keyRepo.addKeysTx(c, player, caseId, amount);
            keyRepo.logTransactionTx(c, player, caseId, amount, reason, adminUuid, serverId);
            newAmountHolder[0] = keyRepo.getAmountTx(c, player, caseId);
            return null;
        });

        final int newAmount = newAmountHolder[0];
        fireKeyChange(player, caseId, amount, newAmount, reason, adminUuid);
        return newAmount;
    }

    /**
     * Списание ключей (ADMIN_TAKE). Возвращает false, если ключей не хватает.
     */
    public boolean takeKeys(@NotNull UUID player,
                            @NotNull String caseId,
                            int amount,
                            @NotNull KeyReason reason,
                            @Nullable UUID adminUuid) throws SQLException {
        if (amount <= 0) return true;

        final boolean[] okHolder = {false};
        final int[] newAmountHolder = {-1};
        TransactionHelper.inTransaction(db, c -> {
            if (!keyRepo.atomicSpend(c, player, caseId, amount)) {
                // Ничего не меняли — commit с пустым телом безопасен, okHolder останется false.
                return null;
            }
            keyRepo.logTransactionTx(c, player, caseId, -amount, reason, adminUuid, serverId);
            newAmountHolder[0] = keyRepo.getAmountTx(c, player, caseId);
            okHolder[0] = true;
            return null;
        });

        if (!okHolder[0]) return false;

        fireKeyChange(player, caseId, -amount, newAmountHolder[0], reason, adminUuid);
        return true;
    }

    /**
     * Установка точного значения (ADMIN_SET).
     */
    public void setKeys(@NotNull UUID player,
                        @NotNull String caseId,
                        int amount,
                        @Nullable UUID adminUuid) throws SQLException {
        final long[] deltaHolder = {0L};
        TransactionHelper.inTransaction(db, c -> {
            int old = keyRepo.getAmountTx(c, player, caseId);
            keyRepo.setKeysTx(c, player, caseId, amount);
            long delta = amount - old;
            deltaHolder[0] = delta;
            if (delta != 0) {
                keyRepo.logTransactionTx(c, player, caseId, delta,
                        KeyReason.ADMIN_SET, adminUuid, serverId);
            }
            return null;
        });

        long delta = deltaHolder[0];
        // Событие фаерим даже при delta == 0 (совместимо с прошлым поведением).
        fireKeyChange(player, caseId, (int) delta, amount, KeyReason.ADMIN_SET, adminUuid);
    }

    /** Событие всегда на main-потоке. */
    private void fireKeyChange(@NotNull UUID player,
                               @NotNull String caseId,
                               int delta,
                               int newAmount,
                               @NotNull KeyReason reason,
                               @Nullable UUID adminUuid) {
        final CaseKeyChangeEvent event = new CaseKeyChangeEvent(
                player, caseId, delta, newAmount, reason, adminUuid);
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getScheduler().runTask(plugin,
                    () -> Bukkit.getPluginManager().callEvent(event));
        }
    }
}