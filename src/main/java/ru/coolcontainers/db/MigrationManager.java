package ru.coolcontainers.db;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.config.PluginConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Свой мигратор (без Flyway).
 *
 * Ключевые моменты:
 *  - DDL в MySQL не транзакционен — rollback бесполезен. Поэтому миграция
 *    считается применённой только после УСПЕШНОГО выполнения всех statement'ов.
 *  - Повторный запуск миграции при уже существующем индексе (MySQL error 1061)
 *    считается безопасным и пропускается.
 *  - Список миграций читается из jar (db/migration/*.sql) и сортируется по
 *    числовому префиксу версии (V1, V2, V10 — а не лексикографически).
 *  - Парсер SQL учитывает кавычки, чтобы `;` внутри литералов не ломал split.
 */
public final class MigrationManager {

    private static final String DIR = "db/migration/";
    private static final String SCHEMA_TABLE = "cc_schema_version";

    /** Fallback, если jar недоступен (нестандартный classloader). */
    private static final List<String> FALLBACK_MIGRATIONS = List.of(
            "V1__init.sql",
            "V2__retention_indexes.sql"
    );

    private MigrationManager() {}

    public static void migrate(@NotNull PluginConfig cfg, @NotNull JavaPlugin plugin)
            throws SQLException, IOException {

        Logger log = plugin.getLogger();

        String url = "jdbc:mysql://" + cfg.getDbHost() + ":" + cfg.getDbPort()
                + "/" + cfg.getDbName()
                + "?useSSL=false&useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=UTC&allowPublicKeyRetrieval=true";

        List<String> migrations = discoverMigrations(plugin, log);
        log.info("Миграции: обнаружено " + migrations.size() + " файлов: " + migrations);

        try (Connection c = DriverManager.getConnection(url, cfg.getDbUser(), cfg.getDbPassword())) {
            ensureSchemaTable(c);

            Set<String> applied = loadApplied(c);
            int appliedCount = 0;

            for (String file : migrations) {
                String version = extractVersion(file);
                if (applied.contains(version)) continue;

                String path = DIR + file;
                String sql = readResource(plugin, path);

                try {
                    executeSql(c, sql, log);
                } catch (SQLException e) {
                    log.severe("Миграция " + version + " (" + file + ") упала: "
                            + e.getMessage() + ". Плагин не будет включён, чтобы не "
                            + "работать на неполной схеме.");
                    throw e;
                }

                recordApplied(c, version);
                appliedCount++;
                log.info("Применена миграция " + version + " (" + file + ")");
            }

            log.info("Миграции: применено " + appliedCount
                    + " из " + migrations.size() + ".");
        }
    }

    // ------------------------------------------------------------------
    //  Обнаружение файлов миграций
    // ------------------------------------------------------------------

    private static @NotNull List<String> discoverMigrations(@NotNull JavaPlugin plugin,
                                                            @NotNull Logger log) {
        List<String> fromJar = readMigrationNamesFromJar(plugin);
        if (fromJar.isEmpty()) {
            log.warning("Не удалось прочитать список миграций из jar — "
                    + "использую статический fallback (" + FALLBACK_MIGRATIONS.size()
                    + " файлов). Не забудь обновить список вручную при добавлении новой миграции.");
            return FALLBACK_MIGRATIONS;
        }
        return fromJar;
    }

    private static @NotNull List<String> readMigrationNamesFromJar(@NotNull JavaPlugin plugin) {
        try {
            var codeSource = plugin.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null) return List.of();
            var url = codeSource.getLocation();
            if (url == null) return List.of();
            File file = new File(url.toURI());
            if (!file.isFile() || !file.getName().endsWith(".jar")) return List.of();

            List<String> names = new ArrayList<>();
            try (ZipFile zip = new ZipFile(file)) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (e.isDirectory()) continue;
                    if (!name.startsWith(DIR)) continue;
                    if (!name.endsWith(".sql")) continue;
                    names.add(name.substring(DIR.length()));
                }
            }
            names.sort(Comparator.comparingInt(MigrationManager::extractVersionNumber));
            return names;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static int extractVersionNumber(@NotNull String filename) {
        // V1__init.sql → 1
        if (!filename.startsWith("V")) return Integer.MAX_VALUE;
        int idx = filename.indexOf("__");
        if (idx <= 1) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(filename.substring(1, idx));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------
    //  Schema-таблица
    // ------------------------------------------------------------------

    private static void ensureSchemaTable(@NotNull Connection c) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + SCHEMA_TABLE + " ("
                + "version VARCHAR(64) NOT NULL, "
                + "applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (version)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static @NotNull Set<String> loadApplied(@NotNull Connection c) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM " + SCHEMA_TABLE)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static @NotNull String readResource(@NotNull JavaPlugin plugin,
                                                @NotNull String path) throws IOException {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                throw new IOException("Ресурс не найден в jar плагина: " + path
                        + ". Проверь, что src/main/resources/" + path + " существует "
                        + "и попадает в CoolContainers-2.0.0.jar.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    //  Выполнение SQL
    // ------------------------------------------------------------------

    /**
     * DDL в MySQL сам auto-commit'ится, поэтому оборачивать в транзакцию смысла нет.
     * Игнорируем MySQL error 1061 (Duplicate key name) — безопасно для CREATE INDEX.
     */
    private static void executeSql(@NotNull Connection c,
                                   @NotNull String rawSql,
                                   @NotNull Logger log) throws SQLException {
        List<String> statements = splitStatements(rawSql);
        try (Statement st = c.createStatement()) {
            for (String stmt : statements) {
                try {
                    st.execute(stmt);
                } catch (SQLException e) {
                    // MySQL: 1061 = ER_DUP_KEYNAME
                    if (e.getErrorCode() == 1061) {
                        log.info("Миграция: индекс уже существует, пропуск: "
                                + abbreviate(stmt));
                        continue;
                    }
                    throw new SQLException("Ошибка в стейтменте: "
                            + abbreviate(stmt) + " → " + e.getMessage(), e);
                }
            }
        }
    }

    private static @NotNull String abbreviate(@NotNull String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 97) + "...";
    }

    /**
     * Разбивает SQL-файл на отдельные стейтменты.
     * Учитывает кавычки (' ", `), чтобы `;` внутри литералов не разрывал стейтмент.
     * Убирает строчные комментарии `-- ...` вне литералов.
     */
    private static @NotNull List<String> splitStatements(@NotNull String rawSql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : rawSql.split("\n", -1)) {
            cleaned.append(stripLineComment(line)).append('\n');
        }

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;

        String src = cleaned.toString();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);

            if (!inSingle && !inDouble && !inBacktick) {
                if (c == '\'') { inSingle = true; current.append(c); continue; }
                if (c == '"')  { inDouble = true; current.append(c); continue; }
                if (c == '`')  { inBacktick = true; current.append(c); continue; }
                if (c == ';') {
                    String s = current.toString().trim();
                    if (!s.isEmpty()) out.add(s);
                    current.setLength(0);
                    continue;
                }
                current.append(c);
            } else {
                // внутри литерала — учитываем escape '\'
                if ((inSingle || inDouble) && c == '\\') {
                    current.append(c);
                    if (i + 1 < src.length()) {
                        current.append(src.charAt(i + 1));
                        i++;
                    }
                    continue;
                }
                if (inSingle && c == '\'') inSingle = false;
                else if (inDouble && c == '"') inDouble = false;
                else if (inBacktick && c == '`') inBacktick = false;
                current.append(c);
            }
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    /** Убирает `-- ...` вне строковых литералов. */
    private static @NotNull String stripLineComment(@NotNull String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (!inSingle && !inDouble) {
                if (c == '\'') inSingle = true;
                else if (c == '"') inDouble = true;
                else if (c == '-' && line.charAt(i + 1) == '-') {
                    return line.substring(0, i);
                }
            } else {
                if (inSingle && c == '\'') inSingle = false;
                else if (inDouble && c == '"') inDouble = false;
            }
        }
        return line;
    }

    private static void recordApplied(@NotNull Connection c, @NotNull String version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + SCHEMA_TABLE + " (version) VALUES (?)")) {
            ps.setString(1, version);
            ps.executeUpdate();
        }
    }

    private static @NotNull String extractVersion(@NotNull String filename) {
        int idx = filename.indexOf("__");
        return idx > 0 ? filename.substring(0, idx) : filename;
    }
}