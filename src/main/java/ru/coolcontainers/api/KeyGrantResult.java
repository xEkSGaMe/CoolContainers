package ru.coolcontainers.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Результат выдачи ключей через {@link CoolContainersAPI#giveKeys}. */
public record KeyGrantResult(boolean success, int newAmount, @Nullable Failure failure) {

    public enum Failure {
        /** Плагин был выключен между получением API и вызовом. */
        PLUGIN_DISABLED,
        /** Кейс не загружен в реестре. */
        UNKNOWN_CASE,
        /** amount <= 0. */
        INVALID_AMOUNT,
        /** Ошибка БД, ключи не начислены. */
        DB_ERROR
    }

    @NotNull
    public static KeyGrantResult ok(int newAmount) {
        return new KeyGrantResult(true, newAmount, null);
    }

    @NotNull
    public static KeyGrantResult fail(@NotNull Failure failure) {
        return new KeyGrantResult(false, 0, failure);
    }
}
