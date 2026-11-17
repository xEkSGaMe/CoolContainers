package ru.coolcontainers.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * До списания ключа. Если cancelled — ключ не списывается, opening не создаётся.
 */
public final class CaseOpenPreEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String caseId;
    private final String blockWorld;
    private final int blockX, blockY, blockZ;

    private boolean cancelled;
    private @Nullable String cancelReason;

    public CaseOpenPreEvent(@NotNull Player player,
                            @NotNull String caseId,
                            @NotNull String blockWorld,
                            int blockX, int blockY, int blockZ) {
        this.player = player;
        this.caseId = caseId;
        this.blockWorld = blockWorld;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
    }

    public @NotNull Player getPlayer() { return player; }
    public @NotNull String getCaseId() { return caseId; }
    public @NotNull String getBlockWorld() { return blockWorld; }
    public int getBlockX() { return blockX; }
    public int getBlockY() { return blockY; }
    public int getBlockZ() { return blockZ; }

    public @Nullable String getCancelReason() { return cancelReason; }
    public void setCancelReason(@Nullable String reason) { this.cancelReason = reason; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}