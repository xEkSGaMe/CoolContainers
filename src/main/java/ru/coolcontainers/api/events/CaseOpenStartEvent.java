package ru.coolcontainers.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** После успешного старта анимации. */
public final class CaseOpenStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID openingId;
    private final Player player;
    private final String caseId;

    public CaseOpenStartEvent(@NotNull UUID openingId,
                              @NotNull Player player,
                              @NotNull String caseId) {
        this.openingId = openingId;
        this.player = player;
        this.caseId = caseId;
    }

    public @NotNull UUID getOpeningId() { return openingId; }
    public @NotNull Player getPlayer() { return player; }
    public @NotNull String getCaseId() { return caseId; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}