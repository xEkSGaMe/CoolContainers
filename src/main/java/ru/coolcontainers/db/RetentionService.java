package ru.coolcontainers.db;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.config.PluginConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Батч-очистка старых записей.
 *
 * Таблицы:
 *   cc_key_transactions   → created_at, retention.key-transactions-days
 *   cc_reward_grants      → created_at, retention.reward-grants-days
 *   cc_admin_logs         → created_at, retention.admin-logs-days
 *   cc_case_openings      → created_at, retention.case-openings-days
 *                            (только терминальные: GRANTED / REFUNDED / FAILED)
 *
 * Запускается по расписанию (interval-minutes) в async-потоке.
 * Удаляет батчами по batch-size, чтобы не лочить таблицы на минуты.
 */
public final class RetentionService {

    public record PurgeReport(String table, int deleted) {}

    private final JavaPlugin plugin;
    private final Logger log;
    private final Database db;
    private final PluginConfig config;

    private int taskId = -1;

    public RetentionService(@NotNull JavaPlugin plugin,
                            @NotNull Database db,
                            @NotNull PluginConfig config) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.db = db;
        this.config = config;
    }

    /** Запустить автоочистку по расписанию. 0 интервал — не запускаем. */
    public void start() {
        int minutes = config.getRetentionIntervalMinutes();
        if (minutes <= 0) {
            log.info("Retention: автоочистка отключена (interval-minutes=0).");
            return;
        }
        long periodTicks = minutes * 60L * 20L;

        // Первый запуск через 5 минут после старта, дальше — по периоду.
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::runOnceSafe,
                5L * 60L * 20L,
                periodTicks
        ).getTaskId();

        log.info("Retention: автоочистка каждые " + minutes + " мин.");
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /** Ручной запуск (для /caseadmin purge). */
    public @NotNull List<PurgeReport> runOnce() throws SQLException {
        List<PurgeReport> reports = new ArrayList<>();

        reports.add(purge("cc_key_transactions", "created_at",
                config.getRetentionKeyTransactionsDays()));
        reports.add(purge("cc_reward_grants", "created_at",
                config.getRetentionRewardGrantsDays()));
        reports.add(purge("cc_admin_logs", "created_at",
                config.getRetentionAdminLogsDays()));
        reports.add(purgeOpeningsOnlyTerminal(config.getRetentionCaseOpeningsDays()));

        return reports;
    }

    private void runOnceSafe() {
        try {
            List<PurgeReport> reports = runOnce();
            int total = reports.stream().mapToInt(PurgeReport::deleted).sum();
            if (total > 0) {
                log.info("Retention: удалено " + total + " строк. " + format(reports));
            }
        } catch (SQLException e) {
            log.warning("Retention: ошибка очистки: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Внутреннее
    // ------------------------------------------------------------------

    /**
     * Удаляет строки из table WHERE column < NOW() - INTERVAL days DAY
     * батчами по batchSize. Возвращает общее число удалённых.
     */
    private @NotNull PurgeReport purge(@NotNull String table,
                                       @NotNull String column,
                                       int days) throws SQLException {
        if (days <= 0) return new PurgeReport(table, 0);

        String sql = "DELETE FROM " + table
                + " WHERE " + column + " < NOW() - INTERVAL ? DAY "
                + "LIMIT ?";
        int batchSize = Math.max(1, config.getRetentionBatchSize());
        int total = 0;

        while (true) {
            int n;
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, days);
                ps.setInt(2, batchSize);
                n = ps.executeUpdate();
            }
            total += n;
            if (n < batchSize) break;
        }
        return new PurgeReport(table, total);
    }

    /**
     * cc_case_openings — удаляем только терминальные (GRANTED / REFUNDED / FAILED).
     * Активные (PENDING/ANIMATING/GRANTING) не трогаем никогда.
     */
    private @NotNull PurgeReport purgeOpeningsOnlyTerminal(int days) throws SQLException {
        if (days <= 0) return new PurgeReport("cc_case_openings", 0);

        String sql = "DELETE FROM cc_case_openings "
                + "WHERE created_at < NOW() - INTERVAL ? DAY "
                + "AND status IN ('GRANTED','REFUNDED','FAILED') "
                + "LIMIT ?";
        int batchSize = Math.max(1, config.getRetentionBatchSize());
        int total = 0;

        while (true) {
            int n;
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, days);
                ps.setInt(2, batchSize);
                n = ps.executeUpdate();
            }
            total += n;
            if (n < batchSize) break;
        }
        return new PurgeReport("cc_case_openings", total);
    }

    private static @NotNull String format(@NotNull List<PurgeReport> reports) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < reports.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(reports.get(i).table()).append('=').append(reports.get(i).deleted());
        }
        return sb.append(')').toString();
    }
}