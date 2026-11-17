package ru.coolcontainers.animations.creeper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public record CreeperSettings(
        int clickTimeoutSeconds,
        int sessionTimeoutSeconds,
        int creeperCount,
        double creeperRadius,
        int spawnIntervalTicks,
        int unselectedRevealTicks,
        int displayTicks
) {
    public static @NotNull CreeperSettings load(@NotNull JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "animations/creeper.yml");
        if (!file.exists()) {
            plugin.saveResource("animations/creeper.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        return new CreeperSettings(
                cfg.getInt("click-timeout-seconds", 15),
                cfg.getInt("session-timeout-seconds", 60),
                Math.max(1, cfg.getInt("creeper-count", 8)),
                Math.max(0.5, cfg.getDouble("creeper-radius", 2.5)),
                Math.max(1, cfg.getInt("spawn-interval-ticks", 4)),
                Math.max(10, cfg.getInt("unselected-reveal-ticks", 40)),
                Math.max(40, cfg.getInt("display-ticks", 120))
        );
    }
}