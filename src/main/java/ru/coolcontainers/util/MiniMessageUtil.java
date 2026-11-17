package ru.coolcontainers.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MiniMessageUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MiniMessageUtil() {}

    public static @NotNull Component parse(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return MM.deserialize(raw);
    }
}