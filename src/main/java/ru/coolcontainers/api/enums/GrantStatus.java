package ru.coolcontainers.api.enums;

/** Статус выдачи награды (cc_reward_grants.status). */
public enum GrantStatus {
    PROCESSING,
    GRANTED,
    FAILED;

    public static GrantStatus fromDb(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}