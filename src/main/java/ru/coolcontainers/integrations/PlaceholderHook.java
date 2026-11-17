package ru.coolcontainers.integrations;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.key.KeyCache;

import java.util.Optional;

/**
 * PlaceholderAPI expansion.
 *   %coolcontainers_keys_<caseId>% — количество ключей игрока (из кэша).
 *
 * Первое обращение к незнакомому игроку/кейсу возвращает 0 и запускает
 * async warm-up; со следующего тика PAPI отдаёт реальное значение.
 * Не блокирует main thread.
 */
public final class PlaceholderHook extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final KeyCache keyCache;

    public PlaceholderHook(@NotNull JavaPlugin plugin, @NotNull KeyCache keyCache) {
        this.plugin = plugin;
        this.keyCache = keyCache;
    }

    public void init() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.getLogger().warning("PlaceholderAPI не найден — плейсхолдеры недоступны.");
            return;
        }
        if (register()) {
            plugin.getLogger().info("PlaceholderAPI expansion зарегистрирован.");
        } else {
            plugin.getLogger().warning("Не удалось зарегистрировать PAPI expansion.");
        }
    }

    @Override public @NotNull String getIdentifier() { return "coolcontainers"; }
    @Override public @NotNull String getAuthor() { return "CoolContainers"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        if (!params.startsWith("keys_")) return null;

        String caseId = params.substring("keys_".length());
        if (caseId.isEmpty()) return "0";

        Optional<Integer> cached = keyCache.peek(player.getUniqueId(), caseId);
        if (cached.isPresent()) return String.valueOf(cached.get());

        keyCache.warmUp(player.getUniqueId(), caseId);
        return "0";
    }
}