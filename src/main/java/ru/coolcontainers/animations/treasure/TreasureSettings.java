package ru.coolcontainers.animations.treasure;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public record TreasureSettings(
        @NotNull Material defaultBlockType,
        int defaultMaxCharges,
        int fillTicks,
        int windupTicks,
        int sessionTimeoutSeconds,
        int riseHeight,
        int riseTicks,
        int displayTicks
) {
    public static @NotNull TreasureSettings load(@NotNull JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "animations/treasure.yml");
        if (!file.exists()) {
            plugin.saveResource("animations/treasure.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        Material defBlock = Material.RESPAWN_ANCHOR;
        String rawBlock = cfg.getString("default-block-type", "RESPAWN_ANCHOR");
        Material parsed = Material.matchMaterial(rawBlock);
        if (parsed != null && parsed.isBlock()) defBlock = parsed;

        return new TreasureSettings(
                defBlock,
                Math.max(1, cfg.getInt("default-max-charges", 4)),
                Math.max(0, cfg.getInt("fill-ticks", 40)),
                Math.max(0, cfg.getInt("windup-ticks", 15)),
                Math.max(10, cfg.getInt("session-timeout-seconds", 60)),
                Math.max(1, cfg.getInt("rise-height", 2)),
                Math.max(1, cfg.getInt("rise-ticks", 25)),
                Math.max(20, cfg.getInt("display-ticks", 100))
        );
    }
}