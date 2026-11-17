package ru.coolcontainers.db.repository;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.db.Database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Привязки блоков кейсов к координатам. Блоки живут только на одном server_id.
 */
public final class BlockRepository {

    public record BlockRow(String serverId,
                           String world,
                           int x, int y, int z,
                           String caseId,
                           Timestamp updatedAt) {}

    private final Database db;

    public BlockRepository(@NotNull Database db) {
        this.db = db;
    }

    public void bind(@NotNull String serverId,
                     @NotNull String world,
                     int x, int y, int z,
                     @NotNull String caseId) throws SQLException {
        String sql = "INSERT INTO cc_case_blocks (server_id, world, x, y, z, case_id) "
                + "VALUES (?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE case_id = VALUES(case_id)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setString(6, caseId);
            ps.executeUpdate();
        }
    }

    public void unbind(@NotNull String serverId,
                       @NotNull String world,
                       int x, int y, int z) throws SQLException {
        String sql = "DELETE FROM cc_case_blocks "
                + "WHERE server_id = ? AND world = ? AND x = ? AND y = ? AND z = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.executeUpdate();
        }
    }

    /**
     * Удалить все блоки указанного кейса на указанном сервере (при /caseadmin delete).
     * Фильтр по server_id обязателен — иначе снесём блоки чужих серверов.
     */
    public void unbindAll(@NotNull String serverId,
                          @NotNull String caseId) throws SQLException {
        String sql = "DELETE FROM cc_case_blocks WHERE case_id = ? AND server_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, caseId);
            ps.setString(2, serverId);
            ps.executeUpdate();
        }
    }

    public @Nullable BlockRow findAt(@NotNull String serverId,
                                     @NotNull String world,
                                     int x, int y, int z) throws SQLException {
        String sql = "SELECT * FROM cc_case_blocks "
                + "WHERE server_id = ? AND world = ? AND x = ? AND y = ? AND z = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public @NotNull List<BlockRow> findAllByServer(@NotNull String serverId) throws SQLException {
        String sql = "SELECT * FROM cc_case_blocks WHERE server_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BlockRow> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public @NotNull List<BlockRow> findAllByCase(@NotNull String caseId) throws SQLException {
        String sql = "SELECT * FROM cc_case_blocks WHERE case_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BlockRow> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    private BlockRow map(ResultSet rs) throws SQLException {
        return new BlockRow(
                rs.getString("server_id"),
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("case_id"),
                rs.getTimestamp("updated_at")
        );
    }

    /** Преобразовать row → Bukkit Location (null, если мир не загружен). */
    public static @Nullable Location toLocation(@NotNull BlockRow row) {
        World world = Bukkit.getWorld(row.world());
        if (world == null) return null;
        return new Location(world, row.x(), row.y(), row.z());
    }
}