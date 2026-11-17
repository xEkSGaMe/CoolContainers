package ru.coolcontainers.animations.creeper;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.animations.AnimationContext;
import ru.coolcontainers.animations.AnimationSession;
import ru.coolcontainers.animations.CaseAnimation;

import java.util.Map;

public final class CreeperAnimation implements CaseAnimation {

    public static final String TYPE = "creeper";

    private final JavaPlugin plugin;
    private final CreeperSettings settings;

    public CreeperAnimation(@NotNull JavaPlugin plugin, @NotNull CreeperSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public @NotNull String type() {
        return TYPE;
    }

    @Override
    public @NotNull AnimationSession create(@NotNull AnimationContext ctx,
                                            @NotNull Map<String, Object> options) {
        return new CreeperSession(ctx, settings, plugin);
    }
}