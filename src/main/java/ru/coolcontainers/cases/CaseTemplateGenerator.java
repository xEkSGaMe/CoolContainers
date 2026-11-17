package ru.coolcontainers.cases;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Копирует дефолтные cases/*.yml из jar, если рабочая папка пуста.
 * Шаблоны лежат в classpath:cases/.
 */
public final class CaseTemplateGenerator {

    private static final List<String> TEMPLATES = List.of(
            "DONATE.yml",
            "SMALL_DONATE.yml",
            "CURRENCY.yml",
            "RUBY.yml",
            "TREASURE_SMALL.yml",
            "TREASURE_MEDIUM.yml",
            "TREASURE_LARGE.yml",
            "CUSTOM.yml"
    );

    private final JavaPlugin plugin;

    public CaseTemplateGenerator(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureDefaults(@NotNull Path casesDir) {
        File dir = casesDir.toFile();
        if (dir.isDirectory() && hasAnyYaml(dir)) {
            return; // уже что-то есть — не трогаем
        }
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Не удалось создать папку: " + casesDir);
            return;
        }

        int created = 0;
        for (String name : TEMPLATES) {
            File target = new File(dir, name);
            if (target.exists()) continue;
            try (InputStream in = plugin.getResource("cases/" + name)) {
                if (in == null) {
                    plugin.getLogger().warning("Шаблон не найден в jar: cases/" + name);
                    continue;
                }
                Files.copy(in, target.toPath());
                created++;
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось создать шаблон " + name + ": " + e.getMessage());
            }
        }
        if (created > 0) {
            plugin.getLogger().info("Создано дефолтных кейсов: " + created);
        }
    }

    private static boolean hasAnyYaml(@NotNull File dir) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        return files != null && files.length > 0;
    }
}