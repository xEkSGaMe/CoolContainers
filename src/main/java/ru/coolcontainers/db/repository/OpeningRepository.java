package ru.coolcontainers.db.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.enums.OpeningStatus;
import ru.coolcontainers.cases.dto.RewardSnapshot;
import ru.coolcontainers.db.Database;
import ru.coolcontainers.util.GsonHolder;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Открытия кейсов.
 */
public final class OpeningRepository {

    public record OpeningRow(UUID openingId,
                             UUID playerUuid,
                             String caseId,
                             int keysSpent,
                             @Nullable RewardSnapshot rewardSnapshot,
                             OpeningStatus status,
                             String serverId,
                             Timestamp createdAt,
                             @Nullable Timestamp completedAt) {}

    private final Database db;

    public OpeningRepository(@NotNull Database db) {
        this.db = db;
    }

    /** INSERT cc_case_openings (PENDING). Соединение передаётся снаружи (транзакция). */
    public void insertPending(@NotNull Connection c,
                              @NotNull UUID openingId,
                              @NotNull UUID player,
                              @NotNull String caseId,
                              int keysSpent,
                              @NotNull String serverId) throws SQLException {
        String sql = "INSERT INTO cc_case_openings "
                + "(opening_id, player_uuid, case_id, keys_spent, status, server_id) "
                + "VALUES (?, ?, ?, ?, 'PENDING', ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, openingId.toString());
            ps.setString(2, player.toString());
            ps.setString(3, caseId);
            ps.setInt(4, keysSpent);
            ps.setString(5, serverId);
            ps.executeUpdate();
        }
    }
    /**
     * P2-3: сохранить reward_snapshot ДО запуска side-effect.
     *
     * Вызывается в той же транзакции, что и переход X→GRANTING (см.
     * RewardGranter.grantInternal). Благодаря этому:
     *  - при падении сервера между INSERT PROCESSING и side-effect
     *    на recovery можно прочитать, ЧТО игроку выпало;
     *  - /caseadmin opening показывает reward_snapshot даже для застрявших
     *    opening'ов (раньше было NULL до markGranted — то есть никогда для
     *    тех, кто не дошёл до успеха).
     *
     * Идемпотентен: повторная запись того же snapshot просто перезапишет
     * тем же значением.
     */
    public void setRewardSnapshotTx(@NotNull Connection c,
                                    @NotNull UUID openingId,
                                    @NotNull RewardSnapshot snapshot) throws SQLException {
        String sql = "UPDATE cc_case_openings SET reward_snapshot = ? WHERE opening_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, GsonHolder.compact().toJson(snapshot));
            ps.setString(2, openingId.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Атомарный переход статуса (без транзакции, отдельный UPDATE).
     * Возвращает true, если строка обновлена.
     */
    public boolean updateStatus(@NotNull UUID openingId,
                                @NotNull OpeningStatus expected,
                                @NotNull OpeningStatus next) throws SQLException {
        try (Connection c = db.getConnection()) {
            return updateStatusTx(c, openingId, expected, next);
        }
    }

    /**
     * То же, но внутри уже открытой транзакции — соединение снаружи.
     */
    public boolean updateStatusTx(@NotNull Connection c,
                                  @NotNull UUID openingId,
                                  @NotNull OpeningStatus expected,
                                  @NotNull OpeningStatus next) throws SQLException {
        String sql = "UPDATE cc_case_openings SET status = ? "
                + "WHERE opening_id = ? AND status = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, next.name());
            ps.setString(2, openingId.toString());
            ps.setString(3, expected.name());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Установить GRANTED + completed_at = NOW() + reward_snapshot.
     * Работает только из активных статусов — защита от двойной выдачи.
     */
    public boolean markGranted(@NotNull UUID openingId,
                               @NotNull RewardSnapshot snapshot) throws SQLException {
        String sql = "UPDATE cc_case_openings "
                + "SET status = 'GRANTED', completed_at = NOW(), reward_snapshot = ? "
                + "WHERE opening_id = ? AND status IN ('PENDING','ANIMATING','GRANTING')";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, GsonHolder.compact().toJson(snapshot));
            ps.setString(2, openingId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Установить REFUNDED + completed_at = NOW().
     * Работает только из активных статусов.
     */
    public boolean markRefunded(@NotNull UUID openingId) throws SQLException {
        String sql = "UPDATE cc_case_openings "
                + "SET status = 'REFUNDED', completed_at = NOW() "
                + "WHERE opening_id = ? AND status IN ('PENDING','ANIMATING','GRANTING')";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, openingId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Force-переход FAILED → REFUNDED.
     *
     * Используется /caseadmin recover refund для opening'ов, попавших в FAILED
     * (частичный side-effect, ошибка БД, ручной провал grant'а).
     *
     * Работает ТОЛЬКО из FAILED — обычные переходы (PENDING/ANIMATING/GRANTING)
     * идут через {@link #markRefunded(UUID)}. Разделение важно, потому что
     * FAILED = «side-effect мог пройти частично», и админ принимает решение
     * осознанно.
     *
     * Из GRANTED/REFUNDED переход невозможен (терминальные).
     */
    public boolean forceRefunded(@NotNull UUID openingId) throws SQLException {
        String sql = "UPDATE cc_case_openings "
                + "SET status = 'REFUNDED', completed_at = NOW() "
                + "WHERE opening_id = ? AND status = 'FAILED'";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, openingId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Установить FAILED + completed_at = NOW().
     * Работает только из активных статусов. Используется для opening'ов,
     * которые нельзя ни выдать, ни вернуть автоматически.
     */
    public boolean markFailed(@NotNull UUID openingId) throws SQLException {
        String sql = "UPDATE cc_case_openings "
                + "SET status = 'FAILED', completed_at = NOW() "
                + "WHERE opening_id = ? AND status IN ('PENDING','ANIMATING','GRANTING')";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, openingId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Атомарный переход из любого из allowedFrom → next.
     * Используется в /caseadmin recover, где opening может быть в PENDING,
     * ANIMATING или GRANTING.
     */
    public boolean updateStatusFromAny(@NotNull Connection c,
                                       @NotNull UUID openingId,
                                       @NotNull Set<OpeningStatus> allowedFrom,
                                       @NotNull OpeningStatus next) throws SQLException {
        if (allowedFrom.isEmpty()) return false;
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < allowedFrom.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        String sql = "UPDATE cc_case_openings SET status = ? "
                + "WHERE opening_id = ? AND status IN (" + placeholders + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, next.name());
            ps.setString(idx++, openingId.toString());
            for (OpeningStatus s : allowedFrom) {
                ps.setString(idx++, s.name());
            }
            return ps.executeUpdate() == 1;
        }
    }

    public @Nullable OpeningRow findById(@NotNull UUID openingId) throws SQLException {
        String sql = "SELECT * FROM cc_case_openings WHERE opening_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, openingId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /** PENDING + ANIMATING + GRANTING на этом сервере — для recovery при старте. */
    public @NotNull List<OpeningRow> findActiveByServer(@NotNull String serverId) throws SQLException {
        String sql = "SELECT * FROM cc_case_openings "
                + "WHERE server_id = ? AND status IN ('PENDING','ANIMATING','GRANTING') "
                + "ORDER BY created_at ASC";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                List<OpeningRow> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    /** Последние открытия игрока (для /caseadmin logs). */
    public @NotNull List<OpeningRow> findRecent(@NotNull UUID player,
                                                @Nullable String caseId,
                                                int limit) throws SQLException {
        String sql = caseId == null
                ? "SELECT * FROM cc_case_openings WHERE player_uuid = ? "
                + "ORDER BY created_at DESC LIMIT ?"
                : "SELECT * FROM cc_case_openings WHERE player_uuid = ? AND case_id = ? "
                + "ORDER BY created_at DESC LIMIT ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            if (caseId == null) {
                ps.setInt(2, limit);
            } else {
                ps.setString(2, caseId);
                ps.setInt(3, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<OpeningRow> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    private OpeningRow map(ResultSet rs) throws SQLException {
        String snapshotJson = rs.getString("reward_snapshot");
        RewardSnapshot snap = snapshotJson == null ? null
                : GsonHolder.compact().fromJson(snapshotJson, RewardSnapshot.class);

        return new OpeningRow(
                UUID.fromString(rs.getString("opening_id")),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("case_id"),
                rs.getInt("keys_spent"),
                snap,
                OpeningStatus.fromDb(rs.getString("status")),
                rs.getString("server_id"),
                rs.getTimestamp("created_at"),
                rs.getTimestamp("completed_at")
        );
    }
}