package ru.coolcontainers.key;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.api.events.CaseKeyChangeEvent;
import ru.coolcontainers.db.DbExecutor;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory кэш ключей игрока: UUID → (caseId → amount).
 * Обновляется по CaseKeyChangeEvent; warm-up идёт async при первом обращении
 * (или на PlayerJoinEvent).
 *
 * P2-4: warm-up дедуплицируется по (player, caseId), а не по player.
 * Раньше при одновременном запросе двух кейсов для одного игрока второй
 * молча отбрасывался (KeyLineHologramService.tick проходит по нескольким
 * блокам одного игрока в одном тике). Результат: строки ключей второго
 * кейса показывали 0 до следующего изменения ключей (warm-up повторно
 * не запускался, потому что кэш уже «не пуст» — залипание).
 */
public final class KeyCache implements Listener {

    private final KeyService keyService;
    private final DbExecutor dbExecutor;

    private final Map<UUID, Map<String, Integer>> cache = new ConcurrentHashMap<>();

    /** Ключ — "playerUuid|caseId". ConcurrentHashMap.newKeySet() даёт атомарный add. */
    private final Set<String> warming = ConcurrentHashMap.newKeySet();

    public KeyCache(@NotNull KeyService keyService,
                    @NotNull DbExecutor dbExecutor) {
        this.keyService = keyService;
        this.dbExecutor = dbExecutor;
    }

    public @NotNull Optional<Integer> peek(@NotNull UUID player, @NotNull String caseId) {
        Map<String, Integer> m = cache.get(player);
        if (m == null) return Optional.empty();
        return Optional.ofNullable(m.get(caseId));
    }

    public void warmUp(@NotNull UUID player, @NotNull String caseId) {
        String key = warmKey(player, caseId);
        if (!warming.add(key)) return;
        dbExecutor.supply(() -> keyService.getAmount(player, caseId))
                .whenComplete((v, ex) -> {
                    warming.remove(key);
                    if (ex == null && v != null) {
                        put(player, caseId, v);
                    }
                });
    }

    public void put(@NotNull UUID player, @NotNull String caseId, int amount) {
        cache.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .put(caseId, amount);
    }

    public void invalidateAll(@NotNull UUID player) {
        cache.remove(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKeyChange(@NotNull CaseKeyChangeEvent event) {
        put(event.getPlayerUuid(), event.getCaseId(), event.getNewAmount());
    }

    private static @NotNull String warmKey(@NotNull UUID player, @NotNull String caseId) {
        // UUID.toString фиксированного формата, caseId не содержит '|' (валидатор).
        return player + "|" + caseId;
    }
}