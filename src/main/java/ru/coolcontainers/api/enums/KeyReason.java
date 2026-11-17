package ru.coolcontainers.api.enums;

/** Причина изменения ключей (cc_key_transactions.reason). */
public enum KeyReason {
    OPEN,
    ADMIN_GIVE,
    ADMIN_TAKE,
    ADMIN_SET,
    REFUND_ABORTED,
    REFUND_RECOVERY,
    REFUND_TIMEOUT,
    MIGRATION;

    public boolean isRefund() {
        return this == REFUND_ABORTED || this == REFUND_RECOVERY || this == REFUND_TIMEOUT;
    }
}