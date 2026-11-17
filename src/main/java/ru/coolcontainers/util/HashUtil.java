package ru.coolcontainers.util;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {

    private HashUtil() {}

    public static @NotNull String sha256(@NotNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Нормализация текста YAML перед хэшированием (убрать \r, обрезать пробелы). */
    public static @NotNull String normalize(@NotNull String raw) {
        return raw.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}