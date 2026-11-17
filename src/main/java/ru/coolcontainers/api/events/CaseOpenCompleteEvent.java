package ru.coolcontainers.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** После успешного grant. */
public final class CaseOpenCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID openingId;
    private final Player player;
    private final String caseId;
    private final @Nullable String rewardId;
    private final int rewardAmount;

    public CaseOpenCompleteEvent(@NotNull UUID openingId,
                                 @NotNull Player player,
                                 @NotNull String caseId,
                                 @Nullable String rewardId,
                                 int rewardAmount) {
        this.openingId = openingId;
        this.player = player;
        this.caseId = caseId;
        this.rewardId = rewardId;
        this.rewardAmount = rewardAmount;
    }

    public @NotNull UUID getOpeningId() { return openingId; }
    public @NotNull Player getPlayer() { return player; }
    public @NotNull String getCaseId() { return caseId; }
    public @Nullable String getRewardId() { return rewardId; }
    public int getRewardAmount() { return rewardAmount; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}