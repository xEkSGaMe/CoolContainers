package ru.coolcontainers.cases.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class CaseDefinition {

    private final String id;
    private final CaseType type;
    private final String displayName;
    private final String description;
    private final String permission;
    private final HologramConfig hologram;
    private final AnimationConfig animation;
    private final List<Reward> rewards;
    private final String sourceFileName;

    private volatile @Nullable String contentHash;

    public CaseDefinition(@NotNull String id,
                          @NotNull CaseType type,
                          @NotNull String displayName,
                          @NotNull String description,
                          @NotNull String permission,
                          @NotNull HologramConfig hologram,
                          @NotNull AnimationConfig animation,
                          @NotNull List<Reward> rewards,
                          @NotNull String sourceFileName) {
        this.id = id;
        this.type = type;
        this.displayName = displayName;
        this.description = description;
        this.permission = permission;
        this.hologram = hologram;
        this.animation = animation;
        this.rewards = List.copyOf(rewards);
        this.sourceFileName = sourceFileName;
    }

    public @NotNull String getId() { return id; }
    public @NotNull CaseType getType() { return type; }
    public @NotNull String getDisplayName() { return displayName; }
    public @NotNull String getDescription() { return description; }
    public @NotNull String getPermission() { return permission; }
    public @NotNull HologramConfig getHologram() { return hologram; }
    public @NotNull AnimationConfig getAnimation() { return animation; }
    public @NotNull List<Reward> getRewards() { return rewards; }
    public @NotNull String getSourceFileName() { return sourceFileName; }

    public @Nullable String getContentHash() { return contentHash; }
    public void setContentHash(@Nullable String hash) { this.contentHash = hash; }

    // ------------------------------------------------------------------
    //  Immutable-копии (with*)
    // ------------------------------------------------------------------

    public @NotNull CaseDefinition withRewards(@NotNull List<Reward> newRewards) {
        CaseDefinition copy = new CaseDefinition(
                id, type, displayName, description, permission,
                hologram, animation, newRewards, sourceFileName);
        copy.setContentHash(null);
        return copy;
    }

    public @NotNull CaseDefinition withDisplayName(@NotNull String newName) {
        CaseDefinition copy = new CaseDefinition(
                id, type, newName, description, permission,
                hologram, animation, rewards, sourceFileName);
        copy.setContentHash(contentHash);
        return copy;
    }

    public @NotNull CaseDefinition withHologramLines(@NotNull List<String> newLines) {
        HologramConfig newHolo = new HologramConfig(
                hologram.enabled(), newLines, hologram.offsetY());
        CaseDefinition copy = new CaseDefinition(
                id, type, displayName, description, permission,
                newHolo, animation, rewards, sourceFileName);
        copy.setContentHash(contentHash);
        return copy;
    }

    public @NotNull CaseDefinition withAnimationType(@NotNull String newType) {
        AnimationConfig newAnim = new AnimationConfig(newType, animation.options());
        CaseDefinition copy = new CaseDefinition(
                id, type, displayName, description, permission,
                hologram, newAnim, rewards, sourceFileName);
        copy.setContentHash(contentHash);
        return copy;
    }
}