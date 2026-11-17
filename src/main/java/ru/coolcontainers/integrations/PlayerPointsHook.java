package ru.coolcontainers.integrations;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Начисление рубинов через PlayerPoints.
 */
public final class PlayerPointsHook {

    private final JavaPlugin plugin;
    private final Logger log;
    private PlayerPointsAPI api;

    public PlayerPointsHook(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void init() {
        if (Bukkit.getPluginManager().getPlugin("PlayerPoints") == null) {
            log.warning("PlayerPoints не найден — рубины выдаваться не будут.");
            return;
        }
        try {
            PlayerPoints pp = (PlayerPoints) Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (pp == null) {
                log.warning("PlayerPoints найден, но instance=null.");
                return;
            }
            this.api = pp.getAPI();
            if (api == null) {
                log.warning("PlayerPoints.getAPI() вернул null.");
                return;
            }
            log.info("PlayerPoints hook подключён.");
        } catch (Throwable t) {
            this.api = null;
            log.warning("Не удалось получить PlayerPoints API: " + t.getMessage());
        }
    }

    public boolean isAvailable() {
        return api != null;
    }

    public void give(@NotNull UUID playerUuid, int amount) {
        if (api == null) {
            throw new IllegalStateException("PlayerPoints недоступен");
        }
        boolean ok = api.give(playerUuid, amount);
        if (!ok) {
            throw new IllegalStateException("PlayerPoints: give вернул false");
        }
    }
}