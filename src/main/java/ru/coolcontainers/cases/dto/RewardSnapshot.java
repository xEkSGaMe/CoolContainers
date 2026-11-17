package ru.coolcontainers.cases.dto;

import org.jetbrains.annotations.NotNull;

/**
 * Что именно выиграл игрок. Сохраняется в cc_case_openings.reward_snapshot
 * и в cc_reward_grants.snapshot (JSON).
 */
public record RewardSnapshot(@NotNull String rewardId, int amount) {
}