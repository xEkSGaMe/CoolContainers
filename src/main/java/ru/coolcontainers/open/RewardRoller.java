package ru.coolcontainers.open;

import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Взвешенный ролл одной награды.
 * Сумма шансов кейса уже проверена CaseValidator'ом (= 100 ± 0.01).
 */
public final class RewardRoller {

    public @NotNull Reward roll(@NotNull CaseDefinition def) {
        List<Reward> rewards = def.getRewards();
        if (rewards.isEmpty()) {
            throw new IllegalStateException("Кейс " + def.getId() + " без наград");
        }

        double total = 0.0;
        for (Reward r : rewards) total += r.getChance();

        double rnd = ThreadLocalRandom.current().nextDouble() * total;
        double acc = 0.0;
        for (Reward r : rewards) {
            acc += r.getChance();
            if (rnd <= acc) return r;
        }
        return rewards.get(rewards.size() - 1);
    }
}