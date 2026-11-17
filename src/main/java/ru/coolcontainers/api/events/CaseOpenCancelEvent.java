package ru.coolcontainers.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.api.enums.KeyReason;

import java.util.UUID;

/** Refund-ветка: REFUND_ABORTED / REFUND_RECOVERY / REFUND_TIMEOUT. */
public final class CaseOpenCancelEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID openingId;
    private final Player player;
    private final String caseId;
    private final KeyReason reason;

    public CaseOpenCancelEvent(@NotNull UUID openingId,
                               @NotNull Player player,
                               @NotNull String caseId,
                               @NotNull KeyReason reason) {
        this.openingId = openingId;
        this.player = player;
        this.caseId = caseId;
        this.reason = reason;
    }

    public @NotNull UUID getOpeningId() { return openingId; }
    public @NotNull Player getPlayer() { return player; }
    public @NotNull String getCaseId() { return caseId; }
    public @NotNull KeyReason getReason() { return reason; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}