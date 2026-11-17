package ru.coolcontainers.db.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.db.Database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ключи игроков. Атомарные операции.
 */
public final class KeyRepository {

    public record KeyTx(long id,
                        UUID playerUuid,
                        String caseId,
                        long delta,
                        KeyReason reason,
                        @Nullable UUID adminUuid,
                        String serverId,
                        Timestamp createdAt) {}

    private final Database db;

    public KeyRepository(@NotNull Database db) {
        this.db = db;
    }

    public int getAmount(@NotNull UUID player, @NotNull String caseId) throws SQLException {
        String sql = "SELECT amount FROM cc_case_keys WHERE player_uuid = ? AND case_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** SELECT amount внутри уже открытой транзакции (соединение снаружи). */
    public int getAmountTx(@NotNull Connection c,
                           @NotNull UUID player,
                           @NotNull String caseId) throws SQLException {
        String sql = "SELECT amount FROM cc_case_keys WHERE player_uuid = ? AND case_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean atomicSpend(@NotNull Connection c,
                               @NotNull UUID player,
                               @NotNull String caseId,
                               int amount) throws SQLException {
        String sql = "UPDATE cc_case_keys "
                + "SET amount = amount - ?, version = version + 1, updated_at = NOW() "
                + "WHERE player_uuid = ? AND case_id = ? AND amount >= ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setString(2, player.toString());
            ps.setString(3, caseId);
            ps.setInt(4, amount);
            return ps.executeUpdate() == 1;
        }
    }

    public int addKeys(@NotNull UUID player, @NotNull String caseId, int delta) throws SQLException {
        String sql = "INSERT INTO cc_case_keys (player_uuid, case_id, amount, version) "
                + "VALUES (?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount), version = version + 1";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, caseId);
            ps.setInt(3, delta);
            ps.executeUpdate();
        }
        return getAmount(player, caseId);
    }

    /**
     * P2-6: addKeys внутри уже открытой транзакции.
     * Используется из KeyService, чтобы счетчик и лог были в одной транзакции.
     */
    public void addKeysTx(@NotNull Connection c,
                          @NotNull UUID player,
                          @NotNull String caseId,
                          int delta) throws SQLException {
        String sql = "INSERT INTO cc_case_keys (player_uuid, case_id, amount, version) "
                + "VALUES (?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount), version = version + 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, caseId);
            ps.setInt(3, delta);
            ps.executeUpdate();
        }
    }

    public void setKeys(@NotNull UUID player, @NotNull String caseId, int amount) throws SQLException {
        String sql = "INSERT INTO cc_case_keys (player_uuid, case_id, amount, version) "
                + "VALUES (?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE amount = VALUES(amount), version = version + 1";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, caseId);
            ps.setInt(3, amount);
            ps.executeUpdate();
        }
    }

    /**
     * P2-6: setKeys внутри уже открытой транзакции.
     */
    public void setKeysTx(@NotNull Connection c,
                          @NotNull UUID player,
                          @NotNull String caseId,
                          int amount) throws SQLException {
        String sql = "INSERT INTO cc_case_keys (player_uuid, case_id, amount, version) "
                + "VALUES (?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE amount = VALUES(amount), version = version + 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, caseId);
            ps.setInt(3, amount);
            ps.executeUpdate();
        }
    }

    public void logTransaction(@NotNull UUID player,
                               @NotNull String caseId,
                               long delta,
                               @NotNull KeyReason reason,
                               @Nullable UUID adminUuid,
                               @NotNull String serverId) throws SQLException {
        String sql = "INSERT INTO cc_key_transactions "
                + "(player_uuid, case_id, delta, reason, admin, server_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, caseId);
            ps.setLong(3, delta);
            ps.setString(4, reason.name());
            ps.setString(5, adminUuid == null ? null : adminUuid.toString());
            ps.setString(6, serverId);
            ps.executeUpdate();
        }
    }

    /** logTransaction внутри уже открытой транзакции (соединение снаружи). */
    public void logTransactionTx(@NotNull Connection c,
                                 @NotNull UUID player,
                                 @NotNull String caseId,
                                 long delta,
                                 @NotNull KeyReason reason,
                                 @Nullable UUID adminUuid,
                                 @NotNull String serverId) throws SQLException {
        String sql = "INSERT INTO cc_key_transactions "
                + "(player_uuid, case_id, delta, reason, admin, server_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, caseId);
            ps.setLong(3, delta);
            ps.setString(4, reason.name());
            ps.setString(5, adminUuid == null ? null : adminUuid.toString());
            ps.setString(6, serverId);
            ps.executeUpdate();
        }
    }

    /** История транзакций ключей (для /caseadmin history). */
    public @NotNull List<KeyTx> findTransactions(@NotNull UUID player,
                                                 @Nullable String caseId,
                                                 int limit) throws SQLException {
        String sql = caseId == null
                ? "SELECT * FROM cc_key_transactions WHERE player_uuid = ? "
                + "ORDER BY created_at DESC LIMIT ?"
                : "SELECT * FROM cc_key_transactions WHERE player_uuid = ? AND case_id = ? "
                + "ORDER BY created_at DESC LIMIT ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            if (caseId == null) ps.setInt(2, limit);
            else { ps.setString(2, caseId); ps.setInt(3, limit); }

            try (ResultSet rs = ps.executeQuery()) {
                List<KeyTx> out = new ArrayList<>();
                while (rs.next()) {
                    String admin = rs.getString("admin");
                    out.add(new KeyTx(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("case_id"),
                            rs.getLong("delta"),
                            KeyReason.valueOf(rs.getString("reason")),
                            admin == null ? null : UUID.fromString(admin),
                            rs.getString("server_id"),
                            rs.getTimestamp("created_at")
                    ));
                }
                return out;
            }
        }
    }
}