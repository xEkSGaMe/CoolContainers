package ru.coolcontainers.animations;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Абстракция типа анимации. */
public interface CaseAnimation {

    /** Идентификатор типа (совпадает с animation.type в YAML кейса). */
    @NotNull String type();

    /** Создать сессию этой анимации. */
    @NotNull AnimationSession create(@NotNull AnimationContext ctx,
                                     @NotNull Map<String, Object> options);
}