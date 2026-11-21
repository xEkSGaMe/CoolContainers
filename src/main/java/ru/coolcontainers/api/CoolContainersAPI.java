package ru.coolcontainers.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.enums.KeyReason;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Публичный API для других плагинов. НЕ хранит внутренние сервисы наружу —
 * только безопасные операции с ключами.
 *
 * <p>Как получить:</p>
 * <pre>{@code
 * CoolContainersAPI api = CoolContainersAPI.get();
 * if (api == null) return; // CoolContainers не установлен/не включён
 * }</pre>
 *
 * <p>Как выдать ключи:</p>
 * <pre>{@code
 * api.giveKeys(playerUuid, "RUBY", 5, "MyDonatePlugin", donorUuid)
 *    .thenAccept(result -> {
 *        if (result.success()) {
 *            // result.newAmount() — сколько ключей стало
 *        }
 *    });
 * }</pre>
 *
 * <p>Все методы можно вызывать с любого потока: SQL уходит в пул CoolContainers,
 * {@link ru.coolcontainers.api.events.CaseKeyChangeEvent} фаерится на main-потоке.</p>
 *
 * <p>Выданные ключи попадают в {@code cc_key_transactions} с причиной
 * {@link KeyReason#PLUGIN_GIVE}, а в {@code cc_admin_logs} пишется имя плагина
 * {@code sourcePlugin}. Игрок может быть оффлайн — нужен только его UUID.</p>
 */
public interface CoolContainersAPI {

    /** @return API или {@code null}, если CoolContainers не включён. */
    static @Nullable CoolContainersAPI get() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoolContainers");
        return plugin instanceof CoolContainersAPI api && plugin.isEnabled() ? api : null;
    }

    /** Регистронезависимая проверка, что кейс с таким id загружен. */
    boolean hasCase(@NotNull String caseId);

    /**
     * Начислить ключи игроку.
     *
     * @param player       UUID игрока (можно оффлайн)
     * @param caseId       id кейса, регистр не важен
     * @param amount       количество, должно быть {@code > 0}
     * @param sourcePlugin имя плагина-инициатора, попадёт в аудит
     * @param sourcePlayer UUID инициатора/донора или {@code null} для системной выдачи
     * @return результат с новым количеством ключей или причиной отказа
     */
    @NotNull CompletableFuture<KeyGrantResult> giveKeys(@NotNull UUID player,
                                                         @NotNull String caseId,
                                                         int amount,
                                                         @NotNull String sourcePlugin,
                                                         @Nullable UUID sourcePlayer);

    /** Текущее количество ключей. Регистр {@code caseId} не важен. */
    @NotNull CompletableFuture<Integer> getKeys(@NotNull UUID player, @NotNull String caseId);
}
