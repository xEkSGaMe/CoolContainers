package ru.coolcontainers.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Типизированная обёртка над config.yml.
 */
public final class PluginConfig {

    private final String serverId;

    private final String dbHost;
    private final int dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPassword;
    private final int dbPoolSize;
    private final int dbAsyncThreads;

    private final String blocksAllowedWorld;
    private final String blocksHostServerId;
    private final int blocksCooldownSeconds;

    private final int maxConcurrentAnimations;

    private final int retentionKeyTransactionsDays;
    private final int retentionRewardGrantsDays;
    private final int retentionAdminLogsDays;
    private final int retentionCaseOpeningsDays;
    private final int retentionBatchSize;
    private final int retentionIntervalMinutes;

    private PluginConfig(String serverId,
                         String dbHost, int dbPort, String dbName,
                         String dbUser, String dbPassword, int dbPoolSize,
                         int dbAsyncThreads,
                         String blocksAllowedWorld, String blocksHostServerId,
                         int blocksCooldownSeconds,
                         int maxConcurrentAnimations,
                         int retentionKeyTransactionsDays,
                         int retentionRewardGrantsDays,
                         int retentionAdminLogsDays,
                         int retentionCaseOpeningsDays,
                         int retentionBatchSize,
                         int retentionIntervalMinutes) {
        this.serverId = serverId;
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.dbPoolSize = dbPoolSize;
        this.dbAsyncThreads = dbAsyncThreads;
        this.blocksAllowedWorld = blocksAllowedWorld;
        this.blocksHostServerId = blocksHostServerId;
        this.blocksCooldownSeconds = blocksCooldownSeconds;
        this.maxConcurrentAnimations = maxConcurrentAnimations;
        this.retentionKeyTransactionsDays = retentionKeyTransactionsDays;
        this.retentionRewardGrantsDays = retentionRewardGrantsDays;
        this.retentionAdminLogsDays = retentionAdminLogsDays;
        this.retentionCaseOpeningsDays = retentionCaseOpeningsDays;
        this.retentionBatchSize = retentionBatchSize;
        this.retentionIntervalMinutes = retentionIntervalMinutes;
    }

    public static @NotNull PluginConfig load(@NotNull JavaPlugin plugin) {
        var cfg = plugin.getConfig();

        return new PluginConfig(
                cfg.getString("server-id", "towny"),

                cfg.getString("database.host", "127.0.0.1"),
                cfg.getInt("database.port", 3306),
                cfg.getString("database.name", "coolcontainers"),
                cfg.getString("database.user", "root"),
                cfg.getString("database.password", ""),
                cfg.getInt("database.pool-size", 16),
                cfg.getInt("database.async-threads", 4),

                cfg.getString("blocks.allowed-world", "world"),
                cfg.getString("blocks.host-server-id", "towny"),
                cfg.getInt("blocks.cooldown-seconds", 3),

                cfg.getInt("animations.max-concurrent", 10),

                cfg.getInt("retention.key-transactions-days", 14),
                cfg.getInt("retention.reward-grants-days", 14),
                cfg.getInt("retention.admin-logs-days", 14),
                cfg.getInt("retention.case-openings-days", 30),
                cfg.getInt("retention.batch-size", 1000),
                cfg.getInt("retention.interval-minutes", 60)
        );
    }

    public @NotNull String getServerId() { return serverId; }

    public @NotNull String getDbHost() { return dbHost; }
    public int getDbPort() { return dbPort; }
    public @NotNull String getDbName() { return dbName; }
    public @NotNull String getDbUser() { return dbUser; }
    public @NotNull String getDbPassword() { return dbPassword; }
    public int getDbPoolSize() { return dbPoolSize; }
    public int getDbAsyncThreads() { return dbAsyncThreads; }

    public @NotNull String getBlocksAllowedWorld() { return blocksAllowedWorld; }
    public @NotNull String getBlocksHostServerId() { return blocksHostServerId; }
    public int getBlocksCooldownSeconds() { return blocksCooldownSeconds; }

    public int getMaxConcurrentAnimations() { return maxConcurrentAnimations; }

    public int getRetentionKeyTransactionsDays() { return retentionKeyTransactionsDays; }
    public int getRetentionRewardGrantsDays() { return retentionRewardGrantsDays; }
    public int getRetentionAdminLogsDays() { return retentionAdminLogsDays; }
    public int getRetentionCaseOpeningsDays() { return retentionCaseOpeningsDays; }
    public int getRetentionBatchSize() { return retentionBatchSize; }
    public int getRetentionIntervalMinutes() { return retentionIntervalMinutes; }
}