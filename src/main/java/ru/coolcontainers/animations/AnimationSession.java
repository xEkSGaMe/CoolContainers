package ru.coolcontainers.animations;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.block.BlockKey;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;

import java.util.Collection;
import java.util.UUID;

/**
 * Контракт сессии одной анимации открытия кейса.
 *
 * Реализуется каждым типом анимации (например, {@code CreeperSession}).
 * Менеджер ({@link AnimationService}) каждый тик вызывает {@link #tick(long)},
 * регистрирует {@link #entities()} в реестре, а по завершении — либо
 * {@link #cleanup()} + grant (если {@link #state()} == COMPLETED),
 * либо {@link #cleanup()} + refund (если CANCELLED).
 */
public interface AnimationSession {

    /** Состояние сессии. */
    enum State {
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    @NotNull UUID sessionId();

    @NotNull UUID openingId();

    @NotNull Player player();

    @NotNull CaseDefinition definition();

    /**
     * Итоговая награда. Для анимаций с предварительным роллом на каждого
     * «кандидата» (creeper) возвращает награду, выбранную игроком.
     */
    @NotNull Reward reward();

    @NotNull BlockKey blockKey();

    @NotNull State state();

    @Nullable KeyReason cancelReason();

    /** Сущности, заспавненные этой сессией (creepers, drops, displays). */
    @NotNull Collection<Entity> entities();

    /** Перевести в CANCELLED. Может вызываться из listener'ов. */
    void cancel(@NotNull KeyReason reason);

    /** Продвинуть анимацию. Вызывается каждый тик main-потоком. */
    void tick(long currentTick);

    /** @return true, если клик «съеден» анимацией (надо отменить event). */
    boolean onEntityClick(@NotNull Entity entity, @NotNull Player clicker);

    /** Снести все сущности/голограммы, заспавненные сессией. */
    void cleanup();
}