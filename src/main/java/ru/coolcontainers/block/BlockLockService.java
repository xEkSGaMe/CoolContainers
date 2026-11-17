package ru.coolcontainers.block;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Блокировки блоков кейсов:
 *  - "animation lock" — на блоке идёт анимация, никто другой не может открыть;
 *  - "cooldown" — 3 сек после завершения анимации.
 *
 * Живёт только в памяти.
 */
public final class BlockLockService {

    private static final class LockInfo {
        boolean animating;
        long cooldownUntilMs;
    }

    private final Map<BlockKey, LockInfo> locks = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public BlockLockService(long cooldownSeconds) {
        this.cooldownMs = cooldownSeconds * 1000L;
    }

    /** Может ли блок быть открыт прямо сейчас. */
    public boolean canOpen(@NotNull BlockKey key) {
        LockInfo info = locks.get(key);
        if (info == null) return true;
        if (info.animating) return false;
        return System.currentTimeMillis() >= info.cooldownUntilMs;
    }

    /** Занять блок под анимацию. false — если уже занят / на КД. */
    public boolean lockForAnimation(@NotNull BlockKey key) {
        boolean[] locked = {false};
        locks.compute(key, (k, info) -> {
            long now = System.currentTimeMillis();
            if (info == null) {
                LockInfo fresh = new LockInfo();
                fresh.animating = true;
                locked[0] = true;
                return fresh;
            }
            if (info.animating) return info;              // уже занят
            if (now < info.cooldownUntilMs) return info;  // на КД
            info.animating = true;
            locked[0] = true;
            return info;
        });
        return locked[0];
    }

    // === patch #8: удалён мёртвый метод canOpenAfterLock ===

    /** Завершить анимацию и запустить КД. */
    public void unlockAndCooldown(@NotNull BlockKey key) {
        locks.compute(key, (k, info) -> {
            LockInfo fresh = (info == null) ? new LockInfo() : info;
            fresh.animating = false;
            fresh.cooldownUntilMs = System.currentTimeMillis() + cooldownMs;
            return fresh;
        });
    }

    /** Немедленно снять анимацию без КД (аварийный случай / отмена). */
    public void forceUnlock(@NotNull BlockKey key) {
        locks.computeIfPresent(key, (k, info) -> {
            info.animating = false;
            return info;
        });
    }

    public boolean isAnimating(@NotNull BlockKey key) {
        LockInfo info = locks.get(key);
        return info != null && info.animating;
    }

    public boolean isOnCooldown(@NotNull BlockKey key) {
        LockInfo info = locks.get(key);
        return info != null && !info.animating && System.currentTimeMillis() < info.cooldownUntilMs;
    }
}