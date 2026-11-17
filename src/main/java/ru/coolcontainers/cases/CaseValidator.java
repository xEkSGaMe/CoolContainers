package ru.coolcontainers.cases;

import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Проверка корректности кейса после загрузки.
 * Пустой список = всё ок.
 *
 * Сумма шансов НЕ проверяется: RewardRoller нормализует сумму сам,
 * распределение пропорционально. Жёсткая проверка ломала reload
 * после правок через GUI.
 */
public final class CaseValidator {

    private CaseValidator() {}

    public static @NotNull List<String> validate(@NotNull CaseDefinition def) {
        List<String> errors = new ArrayList<>();

        if (def.getId() == null || def.getId().isBlank()) {
            errors.add("id кейса пуст");
        } else if (!def.getId().equals(def.getId().toLowerCase())) {
            errors.add("id кейса должен быть в нижнем регистре: " + def.getId());
        } else if (!def.getId().matches("[a-z0-9_\\-]+")) {
            errors.add("id кейса содержит недопустимые символы (a-z, 0-9, _, -): " + def.getId());
        }

        if (def.getRewards().isEmpty()) {
            errors.add("в кейсе нет ни одной награды");
        }

        Set<String> rewardIds = new HashSet<>();
        for (Reward r : def.getRewards()) {
            if (r.getId() == null || r.getId().isBlank()) {
                errors.add("у награды пустой id");
                continue;
            }
            if (!rewardIds.add(r.getId())) {
                errors.add("дубликат reward.id: " + r.getId());
            }
            if (r.getChance() < 0) {
                errors.add("reward '" + r.getId() + "': отрицательный шанс");
            }
            if (r.getAmount() <= 0) {
                errors.add("reward '" + r.getId() + "': amount должен быть > 0");
            }
        }

        return errors;
    }
}