package ru.coolcontainers.animations;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.block.BlockKey;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.open.RewardRoller;

import java.util.UUID;

/**
 * Контекст сессии.
 *
 * Раньше содержал Reward (один ролл на всю сессию). Теперь содержит
 * RewardRoller — сессия сама роллит награды (например, creeper роллит
 * по одной на каждого крипера, и выбор игрока определяет итоговую).
 */
public record AnimationContext(
        @NotNull UUID sessionId,
        @NotNull UUID openingId,
        @NotNull Player player,
        @NotNull CaseDefinition definition,
        @NotNull RewardRoller roller,
        @NotNull Location blockLocation,
        @NotNull BlockKey blockKey
) {}