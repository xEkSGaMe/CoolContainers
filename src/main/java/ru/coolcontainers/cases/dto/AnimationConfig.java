package ru.coolcontainers.cases.dto;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Настройки анимации (тип + опции). */
public record AnimationConfig(
        @NotNull String type,
        @NotNull Map<String, Object> options
) {
    public static final String TYPE_CREEPER  = "creeper";
    public static final String TYPE_TREASURE = "treasure";
    public static final String TYPE_NONE     = "none";

    public static AnimationConfig creeper() {
        return new AnimationConfig(TYPE_CREEPER, Map.of());
    }

    public static AnimationConfig treasure() {
        return new AnimationConfig(TYPE_TREASURE, Map.of());
    }

    public static AnimationConfig none() {
        return new AnimationConfig(TYPE_NONE, Map.of());
    }

    public boolean isNone() {
        return TYPE_NONE.equalsIgnoreCase(type);
    }
}