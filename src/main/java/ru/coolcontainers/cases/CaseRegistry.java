package ru.coolcontainers.cases;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.cases.dto.CaseDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory реестр определений кейсов.
 */
public final class CaseRegistry {

    private final Map<String, CaseDefinition> cases = new LinkedHashMap<>();

    public void clear() {
        cases.clear();
    }

    public void register(@NotNull CaseDefinition def) {
        cases.put(def.getId(), def);
    }

    public void unregister(@NotNull String id) {
        cases.remove(id);
    }

    public @Nullable CaseDefinition get(@NotNull String id) {
        return cases.get(id);
    }

    public boolean has(@NotNull String id) {
        return cases.containsKey(id);
    }

    public @NotNull Collection<CaseDefinition> all() {
        return Collections.unmodifiableCollection(cases.values());
    }

    public @NotNull Set<String> ids() {
        return Collections.unmodifiableSet(cases.keySet());
    }

    public int size() {
        return cases.size();
    }
}