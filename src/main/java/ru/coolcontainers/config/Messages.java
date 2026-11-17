package ru.coolcontainers.config;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.util.MiniMessageUtil;

import java.io.File;
import java.util.Map;

/**
 * Обёртка над messages.yml.
 * Поддерживает плейсхолдеры вида {key}.
 */
public final class Messages {

    private final YamlConfiguration yaml;
    private final String prefixRaw;

    private Messages(@NotNull YamlConfiguration yaml, @NotNull String prefixRaw) {
        this.yaml = yaml;
        this.prefixRaw = prefixRaw;
    }

    public static @NotNull Messages load(@NotNull JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        // Подстраховка: если чего-то нет — берём из jar
        try (var in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration jarCfg = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)
                );
                cfg.setDefaults(jarCfg);
                cfg.options().copyDefaults(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось прочитать дефолтный messages.yml: " + e.getMessage());
        }

        String prefix = cfg.getString("prefix", "");
        return new Messages(cfg, prefix);
    }

    /** Сырая строка сообщения (без парсинга MiniMessage). */
    public @NotNull String raw(@NotNull String path, @NotNull Map<String, String> placeholders) {
        String s = yaml.getString(path, "");
        for (var e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    /** Component с добавленным prefix'ом. */
    public @NotNull Component prefixed(@NotNull String path, @NotNull Map<String, String> placeholders) {
        String body = raw(path, placeholders);
        return MiniMessageUtil.parse(prefixRaw + body);
    }

    /** Component без prefix'а. */
    public @NotNull Component component(@NotNull String path, @NotNull Map<String, String> placeholders) {
        return MiniMessageUtil.parse(raw(path, placeholders));
    }
}