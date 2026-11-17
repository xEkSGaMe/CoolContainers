package ru.coolcontainers.db.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.db.Database;
import ru.coolcontainers.util.GsonHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

public final class AdminLogRepository {

    private final Database db;

    public AdminLogRepository(@NotNull Database db) {
        this.db = db;
    }

    public void log(@NotNull UUID adminUuid,
                    @NotNull String action,
                    @Nullable Object payload) throws SQLException {
        String sql = "INSERT INTO cc_admin_logs (admin_uuid, action, payload) VALUES (?, ?, ?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, adminUuid.toString());
            ps.setString(2, action);
            ps.setString(3, payload == null ? null : GsonHolder.compact().toJson(payload));
            ps.executeUpdate();
        }
    }

    public void logSystem(@NotNull String action, @Nullable Object payload) throws SQLException {
        log(new UUID(0L, 0L), action, payload);
    }
}