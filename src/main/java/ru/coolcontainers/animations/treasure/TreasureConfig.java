package ru.coolcontainers.animations.treasure;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Итоговая конфигурация одной treasure-сессии.
 * Собирается из {@link TreasureSettings} (глобальные дефолты)
 * + options из YAML кейса (приоритет у кейса).
 */
public record TreasureConfig(
        @NotNull Material blockType,
        int maxCharges,
        int fillTicks,
        int windupTicks,
        int sessionTimeoutSeconds,
        int riseHeight,
        int riseTicks,
        int displayTicks
) {
    public static @NotNull TreasureConfig from(@NotNull TreasureSettings base,
                                               @NotNull Map<String, Object> options) {
        Material blockType = parseMaterial(options.get("block-type"), base.defaultBlockType());
        int maxCharges = Math.max(1, parseInt(options.get("max-charges"), base.defaultMaxCharges()));
        int fillTicks = Math.max(0, parseInt(options.get("fill-ticks"), base.fillTicks()));
        int windupTicks = Math.max(0, parseInt(options.get("windup-ticks"), base.windupTicks()));
        int sessionTimeout = Math.max(10,
                parseInt(options.get("session-timeout-seconds"), base.sessionTimeoutSeconds()));
        int riseHeight = Math.max(1, parseInt(options.get("rise-height"), base.riseHeight()));
        int riseTicks = Math.max(1, parseInt(options.get("rise-ticks"), base.riseTicks()));
        int displayTicks = Math.max(20, parseInt(options.get("display-ticks"), base.displayTicks()));

        return new TreasureConfig(blockType, maxCharges,
                fillTicks, windupTicks, sessionTimeout,
                riseHeight, riseTicks, displayTicks);
    }

    private static @NotNull Material parseMaterial(Object raw, @NotNull Material fallback) {
        if (raw == null) return fallback;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return fallback;
        Material m = Material.matchMaterial(s);
        if (m == null || !m.isBlock()) return fallback;
        return m;
    }

    private static int parseInt(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(raw).trim()); }
        catch (NumberFormatException e) { return fallback; }
    }
}