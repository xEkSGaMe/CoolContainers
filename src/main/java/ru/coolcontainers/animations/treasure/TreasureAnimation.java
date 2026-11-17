package ru.coolcontainers.animations.treasure;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.animations.AnimationContext;
import ru.coolcontainers.animations.AnimationSession;
import ru.coolcontainers.animations.CaseAnimation;
import ru.coolcontainers.block.KeyLineHologramService;

import java.util.Map;

public final class TreasureAnimation implements CaseAnimation {

    public static final String TYPE = "treasure";

    private final JavaPlugin plugin;
    private final TreasureSettings settings;
    private final @Nullable KeyLineHologramService keyLineHologramService;

    public TreasureAnimation(@NotNull JavaPlugin plugin,
                             @NotNull TreasureSettings settings,
                             @Nullable KeyLineHologramService keyLineHologramService) {
        this.plugin = plugin;
        this.settings = settings;
        this.keyLineHologramService = keyLineHologramService;
    }

    @Override public @NotNull String type() { return TYPE; }

    @Override
    public @NotNull AnimationSession create(@NotNull AnimationContext ctx,
                                            @NotNull Map<String, Object> options) {
        TreasureConfig cfg = TreasureConfig.from(settings, options);
        return new TreasureSession(ctx, cfg, plugin, keyLineHologramService);
    }
}