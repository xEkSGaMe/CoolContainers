package ru.coolcontainers.animations.bee;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public record BeeSettings(
        int clickTimeoutSeconds,
        int sessionTimeoutSeconds,
        int beeCount,
        double orbitRadius,
        double hoverHeight,
        int spawnIntervalTicks,
        int flightTicks,
        int innerOrbitTicks,
        int expandTicks,
        double innerRadiusFactor,
        double orbitSpeed,
        int unselectedRevealTicks,
        int displayTicks
) {
    public static @NotNull BeeSettings load(@NotNull JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "animations/bee.yml");
        if (!file.exists()) {
            plugin.saveResource("animations/bee.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        return new BeeSettings(
                cfg.getInt("click-timeout-seconds", 20),
                cfg.getInt("session-timeout-seconds", 90),
                Math.max(1, cfg.getInt("bee-count", 8)),
                Math.max(0.5, cfg.getDouble("orbit-radius", 2.5)),
                Math.max(0.5, cfg.getDouble("hover-height", 2.5)),
                Math.max(1, cfg.getInt("spawn-interval-ticks", 6)),
                Math.max(1, cfg.getInt("flight-ticks", 30)),
                Math.max(1, cfg.getInt("inner-orbit-ticks", 30)),
                Math.max(1, cfg.getInt("expand-ticks", 25)),
                Math.min(0.95, Math.max(0.1, cfg.getDouble("inner-radius-factor", 0.5))),
                Math.max(0.01, cfg.getDouble("orbit-speed", 0.06)),
                Math.max(10, cfg.getInt("unselected-reveal-ticks", 50)),
                Math.max(40, cfg.getInt("display-ticks", 160))
        );
    }
}