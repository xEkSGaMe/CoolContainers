package ru.coolcontainers.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.enums.KeyReason;

import java.util.UUID;

/** Любое изменение ключей игрока (начисление/списание). */
public final class CaseKeyChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final String caseId;
    private final int delta;
    private final int newAmount;
    private final KeyReason reason;
    private final @Nullable UUID adminUuid;

    public CaseKeyChangeEvent(@NotNull UUID playerUuid,
                              @NotNull String caseId,
                              int delta,
                              int newAmount,
                              @NotNull KeyReason reason,
                              @Nullable UUID adminUuid) {
        this.playerUuid = playerUuid;
        this.caseId = caseId;
        this.delta = delta;
        this.newAmount = newAmount;
        this.reason = reason;
        this.adminUuid = adminUuid;
    }

    public @NotNull UUID getPlayerUuid() { return playerUuid; }
    public @NotNull String getCaseId() { return caseId; }
    public int getDelta() { return delta; }
    public int getNewAmount() { return newAmount; }
    public @NotNull KeyReason getReason() { return reason; }
    public @Nullable UUID getAdminUuid() { return adminUuid; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}