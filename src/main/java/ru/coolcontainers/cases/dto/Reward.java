package ru.coolcontainers.cases.dto;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Одна награда в кейсе. Может содержать любую комбинацию эффектов.
 * Шанс — в процентах, сумма по всем reward'ам кейса = 100 ± 0.01.
 */
public final class Reward {

    public record LuckPermsEntry(@NotNull String group,
                                 @NotNull String duration,
                                 @NotNull String server) {}

    public record VaultEntry(double amount) {}

    public record PlayerPointsEntry(int amount) {}

    private final String id;
    private final String displayName;    // MiniMessage, для GUI/логов
    private final double chance;
    private final int amount;
    private final List<ItemStack> items;
    private final List<String> commands;
    private final @Nullable LuckPermsEntry luckPerms;
    private final @Nullable VaultEntry vault;
    private final @Nullable PlayerPointsEntry playerPoints;
    private final List<String> broadcast;

    private Reward(Builder b) {
        this.id = b.id;
        this.displayName = b.displayName;
        this.chance = b.chance;
        this.amount = b.amount;
        this.items = List.copyOf(b.items);
        this.commands = List.copyOf(b.commands);
        this.luckPerms = b.luckPerms;
        this.vault = b.vault;
        this.playerPoints = b.playerPoints;
        this.broadcast = List.copyOf(b.broadcast);
    }

    public @NotNull String getId() { return id; }
    public @NotNull String getDisplayName() { return displayName; }
    public double getChance() { return chance; }
    public int getAmount() { return amount; }
    public @NotNull List<ItemStack> getItems() { return items; }
    public @NotNull List<String> getCommands() { return commands; }
    public @Nullable LuckPermsEntry getLuckPerms() { return luckPerms; }
    public @Nullable VaultEntry getVault() { return vault; }
    public @Nullable PlayerPointsEntry getPlayerPoints() { return playerPoints; }
    public @NotNull List<String> getBroadcast() { return broadcast; }

    public static Builder builder(@NotNull String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private String displayName = "";
        private double chance;
        private int amount = 1;
        private List<ItemStack> items = List.of();
        private List<String> commands = List.of();
        private LuckPermsEntry luckPerms;
        private VaultEntry vault;
        private PlayerPointsEntry playerPoints;
        private List<String> broadcast = List.of();

        private Builder(String id) { this.id = id; }

        public Builder displayName(String s) { this.displayName = s; return this; }
        public Builder chance(double c) { this.chance = c; return this; }
        public Builder amount(int a) { this.amount = a; return this; }
        public Builder items(List<ItemStack> items) { this.items = items; return this; }
        public Builder commands(List<String> cmds) { this.commands = cmds; return this; }
        public Builder luckPerms(LuckPermsEntry lp) { this.luckPerms = lp; return this; }
        public Builder vault(VaultEntry v) { this.vault = v; return this; }
        public Builder playerPoints(PlayerPointsEntry pp) { this.playerPoints = pp; return this; }
        public Builder broadcast(List<String> b) { this.broadcast = b; return this; }

        public Reward build() { return new Reward(this); }
    }
}