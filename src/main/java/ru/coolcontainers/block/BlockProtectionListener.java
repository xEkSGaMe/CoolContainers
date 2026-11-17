package ru.coolcontainers.block;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.config.PluginConfig;

/**
 * Защита блоков кейсов. Регистрируется только на host-server-id.
 */
public final class BlockProtectionListener implements Listener {

    private final CaseBlockService blocks;
    private final PluginConfig config;

    public BlockProtectionListener(@NotNull CaseBlockService blocks,
                                   @NotNull PluginConfig config) {
        this.blocks = blocks;
        this.config = config;
    }

    private boolean isOurBlock(@NotNull Block block) {
        if (block.getWorld() == null) return false;
        if (!block.getWorld().getName().equals(config.getBlocksAllowedWorld())) return false;
        return blocks.isCaseBlock(block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        if (isOurBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (isOurBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(@NotNull BlockExplodeEvent event) {
        event.blockList().removeIf(this::isOurBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(@NotNull EntityExplodeEvent event) {
        event.blockList().removeIf(this::isOurBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(@NotNull BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            if (isOurBlock(b)) { event.setCancelled(true); return; }
        }
        // Также проверим целевые позиции
        for (Block b : event.getBlocks()) {
            if (isOurBlock(b.getRelative(event.getDirection()))) {
                event.setCancelled(true); return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(@NotNull BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            if (isOurBlock(b)) { event.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(@NotNull EntityChangeBlockEvent event) {
        if (isOurBlock(event.getBlock())) event.setCancelled(true);
    }
}