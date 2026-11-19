package ru.coolcontainers.animations.treasure;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.animations.AnimationContext;
import ru.coolcontainers.animations.AnimationSession;
import ru.coolcontainers.animations.CaseAnimation;

import java.util.Map;

public final class TreasureAnimation implements CaseAnimation {

    public static final String TYPE = "treasure";

    private final JavaPlugin plugin;
    private final TreasureSettings settings;

    public TreasureAnimation(@NotNull JavaPlugin plugin,
                             @NotNull TreasureSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override public @NotNull String type() { return TYPE; }

    @Override
    public @NotNull AnimationSession create(@NotNull AnimationContext ctx,
                                            @NotNull Map<String, Object> options) {
        TreasureConfig cfg = TreasureConfig.from(settings, options);
        return new TreasureSession(ctx, cfg, plugin);
    }
}
