package ru.coolcontainers.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;

public final class GsonHolder {

    private static final Gson COMPACT = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private static final Gson PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private GsonHolder() {}

    public static @NotNull Gson compact() { return COMPACT; }
    public static @NotNull Gson pretty() { return PRETTY; }
}