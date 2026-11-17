package ru.coolcontainers.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.config.PluginConfig;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Пул соединений HikariCP.
 * Один экземпляр на плагин, живёт всё время работы.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;

    private Database(@NotNull HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static @NotNull Database connect(@NotNull PluginConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + cfg.getDbHost() + ":" + cfg.getDbPort()
                + "/" + cfg.getDbName()
                + "?useSSL=false&useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        hc.setUsername(cfg.getDbUser());
        hc.setPassword(cfg.getDbPassword());

        hc.setPoolName("CoolContainers-Pool");
        hc.setMaximumPoolSize(cfg.getDbPoolSize());
        hc.setMinimumIdle(Math.min(4, cfg.getDbPoolSize()));
        hc.setConnectionTimeout(10_000L);
        hc.setIdleTimeout(60_000L);
        hc.setMaxLifetime(1_800_000L); // 30 минут
        hc.setAutoCommit(true);

        // Драйвер
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");

        return new Database(new HikariDataSource(hc));
    }

    public @NotNull Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean isHealthy() {
        try (Connection c = getConnection()) {
            return c.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}