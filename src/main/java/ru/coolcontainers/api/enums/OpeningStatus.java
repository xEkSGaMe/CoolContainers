package ru.coolcontainers.api.enums;

/**
 * Статус открытия кейса (cc_case_openings.status).
 * Жизненный цикл: PENDING → ANIMATING → GRANTING → GRANTED
 * Ветки: REFUNDED (abort), FAILED (ошибка grant'а).
 */
public enum OpeningStatus {
    PENDING,
    ANIMATING,
    GRANTING,
    GRANTED,
    REFUNDED,
    FAILED;

    public static OpeningStatus fromDb(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isTerminal() {
        return this == GRANTED || this == REFUNDED || this == FAILED;
    }
}