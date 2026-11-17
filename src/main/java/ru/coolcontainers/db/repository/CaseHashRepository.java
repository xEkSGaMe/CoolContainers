package ru.coolcontainers.db.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public final class CaseHashRepository {

    private final Database db;

    public CaseHashRepository(@NotNull Database db) {
        this.db = db;
    }

    public void upsert(@NotNull String caseId,
                       @NotNull String contentHash,
                       @NotNull String serverId) throws SQLException {
        String sql = "INSERT INTO cc_case_hashes (case_id, content_hash, server_id) "
                + "VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE content_hash = VALUES(content_hash), updated_at = NOW()";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, caseId);
            ps.setString(2, contentHash);
            ps.setString(3, serverId);
            ps.executeUpdate();
        }
    }

    public void delete(@NotNull String caseId, @NotNull String serverId) throws SQLException {
        String sql = "DELETE FROM cc_case_hashes WHERE case_id = ? AND server_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, caseId);
            ps.setString(2, serverId);
            ps.executeUpdate();
        }
    }

    /** caseId → hash для указанного сервера. */
    public @NotNull Map<String, String> findAllByServer(@NotNull String serverId) throws SQLException {
        String sql = "SELECT case_id, content_hash FROM cc_case_hashes WHERE server_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, String> out = new HashMap<>();
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
                return out;
            }
        }
    }
}