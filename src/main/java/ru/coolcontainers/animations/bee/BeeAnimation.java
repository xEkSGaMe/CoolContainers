package ru.coolcontainers.animations.bee;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.animations.AnimationContext;
import ru.coolcontainers.animations.AnimationSession;
import ru.coolcontainers.animations.CaseAnimation;

import java.util.Map;

public final class BeeAnimation implements CaseAnimation {

    public static final String TYPE = "bee";

    private final JavaPlugin plugin;
    private final BeeSettings settings;

    public BeeAnimation(@NotNull JavaPlugin plugin, @NotNull BeeSettings settings) {
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
        return new BeeSession(ctx, settings, plugin);
    }
}