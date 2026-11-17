package ru.coolcontainers.block;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

/**
 * Ключ блока в памяти: world + координаты.
 * server_id не нужен — в памяти только блоки этого сервера.
 */
public record BlockKey(@NotNull String world, int x, int y, int z) {

    public static @NotNull BlockKey of(@NotNull Location loc) {
        return new BlockKey(loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static @NotNull BlockKey of(@NotNull Block block) {
        return new BlockKey(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
    }

    public @NotNull Location toLocation(@NotNull World world) {
        return new Location(world, x + 0.5, y + 1.0, z + 0.5);
    }

    public int chunkX() { return x >> 4; }
    public int chunkZ() { return z >> 4; }
}