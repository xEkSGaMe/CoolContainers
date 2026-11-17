package ru.coolcontainers.cases.dto;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Настройки голограммы над блоком кейса (из YAML кейса). */
public record HologramConfig(
        boolean enabled,
        @NotNull List<String> lines,
        double offsetY
) {
    public static HologramConfig disabled() {
        return new HologramConfig(false, List.of(), 1.2);
    }

    public static HologramConfig simple(@NotNull String line, double offsetY) {
        return new HologramConfig(true, List.of(line), offsetY);
    }
}