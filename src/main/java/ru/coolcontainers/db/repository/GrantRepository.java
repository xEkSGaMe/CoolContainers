package ru.coolcontainers.db.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.enums.GrantStatus;
import ru.coolcontainers.cases.dto.RewardSnapshot;
import ru.coolcontainers.db.Database;
import ru.coolcontainers.util.GsonHolder;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Записи о выдаче наград. UNIQUE(opening_id, reward_id).
 */
public final class GrantRepository {

    public record GrantRow(UUID openingId,
                           String rewardId,
                           @Nullable RewardSnapshot snapshot,
                           GrantStatus status,
                           @Nullable String error,
                           String serverId,
                           Timestamp createdAt) {}

    private final Database db;

    public GrantRepository(@NotNull Database db) {
        this.db = db;
    }

    /**
     * Durable INSERT PROCESSING. Соединение передаётся снаружи.
     * Если запись уже есть — игнорируем (idempotent).
     */
    public void insertProcessing(@NotNull Connection c,
                                 @NotNull UUID openingId,
                                 @NotNull String rewardId,
                                 @NotNull RewardSnapshot snapshot,
                                 @NotNull String serverId) throws SQLException {
        String sql = "INSERT IGNORE INTO cc_reward_grants "
                + "(opening_id, reward_id, snapshot, status, server_id) "
                + "VALUES (?, ?, ?, 'PROCESSING', ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, openingId.toString());
            ps.setString(2, rewardId);
            ps.setString(3, GsonHolder.compact().toJson(snapshot));
            ps.setString(4, serverId);
            ps.executeUpdate();
        }
    }

    public void markGranted(@NotNull UUID openingId, @NotNull String rewardId) throws SQLException {
        updateStatus(openingId, rewardId, GrantStatus.GRANTED, null);
    }

    public void markFailed(@NotNull UUID openingId,
                           @NotNull String rewardId,
                           @Nullable String error) throws SQLException {
        updateStatus(openingId, rewardId, GrantStatus.FAILED, error);
    }

    private void updateStatus(@NotNull UUID openingId,
                              @NotNull String rewardId,
                              @NotNull GrantStatus status,
                              @Nullable String error) throws SQLException {
        String sql = "UPDATE cc_reward_grants SET status = ?, error = ? "
                + "WHERE opening_id = ? AND reward_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, error);
            ps.setString(3, openingId.toString());
            ps.setString(4, rewardId);
            ps.executeUpdate();
        }
    }

    /** Пометить все PROCESSING-гранты opening'а как FAILED. Возвращает число изменённых. */
    public int markAllProcessingFailed(@NotNull UUID openingId,
                                       @NotNull String error) throws SQLException {
        String sql = "UPDATE cc_reward_grants SET status = 'FAILED', error = ? "
                + "WHERE opening_id = ? AND status = 'PROCESSING'";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, error);
            ps.setString(2, openingId.toString());
            return ps.executeUpdate();
        }
    }

    /** Все гранты opening'а. */
    public @NotNull List<GrantRow> findByOpening(@NotNull UUID openingId) throws SQLException {
        String sql = "SELECT * FROM cc_reward_grants WHERE opening_id = ? ORDER BY created_at ASC";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, openingId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<GrantRow> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    /** Все PROCESSING на этом сервере — для recovery. */
    public @NotNull List<GrantRow> findProcessingByServer(@NotNull String serverId) throws SQLException {
        String sql = "SELECT * FROM cc_reward_grants WHERE server_id = ? AND status = 'PROCESSING'";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                List<GrantRow> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    private GrantRow map(ResultSet rs) throws SQLException {
        String snapJson = rs.getString("snapshot");
        RewardSnapshot snap = snapJson == null ? null
                : GsonHolder.compact().fromJson(snapJson, RewardSnapshot.class);

        return new GrantRow(
                UUID.fromString(rs.getString("opening_id")),
                rs.getString("reward_id"),
                snap,
                GrantStatus.fromDb(rs.getString("status")),
                rs.getString("error"),
                rs.getString("server_id"),
                rs.getTimestamp("created_at")
        );
    }
}