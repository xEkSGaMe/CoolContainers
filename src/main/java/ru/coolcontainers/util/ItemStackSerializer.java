package ru.coolcontainers.util;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;

/**
 * Сериализация ItemStack через Paper serializeAsBytes/deserializeBytes.
 * Сохраняет NBT, CustomModelData, атрибуты, кастомные модели, энчанты и т.д.
 */
public final class ItemStackSerializer {

    private ItemStackSerializer() {}

    public static @NotNull String toBase64(@NotNull ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static @Nullable ItemStack fromBase64(@Nullable String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        // === patch #12: ловим всё RuntimeException (включая IllegalArgumentException
        // от декодера Base64 и от deserializeBytes на битом NBT) ===
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static @NotNull byte[] toBytes(@NotNull ItemStack item) {
        return item.serializeAsBytes();
    }

    public static @Nullable ItemStack fromBytes(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        // === patch #12 ===
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException e) {
            return null;
        }
    }
}