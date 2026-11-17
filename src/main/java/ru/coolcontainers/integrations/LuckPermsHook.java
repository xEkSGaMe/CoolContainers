package ru.coolcontainers.integrations;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.cases.dto.Reward;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Выдача групп через LuckPerms API.
 * Прямая запись в БД LuckPerms запрещена — только через API.
 *
 * Поддерживает:
 *   duration: permanent | 30d | 12h | 5m | 45s | 2w
 *   server:   "global" (без контекста) или имя сервера (контекст server)
 *
 * P1-5: {@link #grant(UUID, Reward.LuckPermsEntry)} теперь синхронный — ждёт
 * saveUser с таймаутом. Ошибка любого шага (loadUser/saveUser) пробрасывается
 * наверх как Exception, что даёт RewardGranter сигнал «награда не выдана»
 * и переводит opening в FAILED вместо тихого «GRANTED без группы».
 *
 * Блокировка: grant вызывается из async-потока (см. RewardGranter.executeSideEffectAsync),
 * поэтому main-поток не страдает.
 */
public final class LuckPermsHook {

    private static final long TIMEOUT_SECONDS = 5L;

    private final JavaPlugin plugin;
    private final Logger log;
    private LuckPerms api;

    public LuckPermsHook(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /** Регистрируется в onEnable, если LuckPerms найден. */
    public void init() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            log.warning("LuckPerms не найден — донат-группы выдаваться не будут.");
            return;
        }
        try {
            this.api = LuckPermsProvider.get();
            log.info("LuckPerms hook подключён.");
        } catch (Throwable t) {
            this.api = null;
            log.warning("Не удалось получить LuckPerms API: " + t.getMessage());
        }
    }

    public boolean isAvailable() {
        return api != null;
    }

    /**
     * Выдать группу игроку. Синхронный: дожидается загрузки юзера (если нужно)
     * и сохранения ноды. Бросает при любой ошибке.
     *
     * ВАЖНО: не вызывать на main-потоке — внутри используется get() с таймаутом.
     */
    public void grant(@NotNull UUID playerUuid,
                      @NotNull Reward.LuckPermsEntry entry) throws Exception {
        if (api == null) {
            throw new IllegalStateException("LuckPerms недоступен");
        }

        Node node = buildNode(entry);

        User user = api.getUserManager().getUser(playerUuid);
        if (user == null) {
            try {
                user = api.getUserManager().loadUser(playerUuid)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "LP: loadUser(" + playerUuid + ") упал: " + e.getMessage(), e);
            }
        }
        if (user == null) {
            throw new IllegalStateException(
                    "LP: loadUser вернул null для " + playerUuid);
        }

        user.data().add(node);

        try {
            api.getUserManager().saveUser(user).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "LP: saveUser(" + playerUuid + ") упал: " + e.getMessage(), e);
        }
    }

    private @NotNull Node buildNode(@NotNull Reward.LuckPermsEntry entry) {
        InheritanceNode.Builder builder = InheritanceNode.builder(entry.group());

        Duration duration = parseDuration(entry.duration());
        if (duration != null) {
            builder.expiry(duration);
        }

        String server = entry.server();
        if (server != null && !server.isBlank()
                && !"global".equalsIgnoreCase(server)) {
            ContextSet ctx = ImmutableContextSet.of(DefaultContextKeys.SERVER_KEY, server);
            builder.withContext(ctx);
        }

        return builder.build();
    }

    /**
     * "permanent"/null/blank → null (без expiry)
     * "30d" → Duration.ofDays(30); "12h" → ofHours(12); "5m" → ofMinutes(5);
     * "45s" → ofSeconds(45); "2w" → ofDays(14).
     * Если суффикс не распознан — считаем дни.
     */
    private static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank() || "permanent".equalsIgnoreCase(raw)) return null;
        String s = raw.trim().toLowerCase();
        char unit = s.charAt(s.length() - 1);
        long value;
        try {
            value = Long.parseLong(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            try {
                value = Long.parseLong(s);
                return Duration.ofDays(value);
            } catch (NumberFormatException e2) {
                return null;
            }
        }
        return switch (unit) {
            case 's' -> Duration.ofSeconds(value);
            case 'm' -> Duration.ofMinutes(value);
            case 'h' -> Duration.ofHours(value);
            case 'd' -> Duration.ofDays(value);
            case 'w' -> Duration.ofDays(value * 7);
            default -> Duration.ofDays(value);
        };
    }
}