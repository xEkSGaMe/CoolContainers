package ru.coolcontainers.cases.dto;

/** Фиксированные типы кейсов. */
public enum CaseType {
    DONATE,
    SMALL_DONATE,
    CURRENCY,
    RUBY,
    TREASURE_SMALL,
    TREASURE_MEDIUM,
    TREASURE_LARGE,
    CUSTOM;

    public static CaseType fromString(String raw) {
        if (raw == null) return CUSTOM;
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CUSTOM;
        }
    }
}