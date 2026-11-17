package ru.coolcontainers.integrations;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

/**
 * Начисление монет через Vault (совместимо с Essentials/VaultUnlocked).
 */
public final class VaultHook {

    private final JavaPlugin plugin;
    private final Logger log;
    private Economy economy;

    public VaultHook(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void init() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            log.warning("Vault не найден — монеты выдаваться не будут.");
            return;
        }
        try {
            RegisteredServiceProvider<Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                log.warning("Vault найден, но Economy-провайдер не зарегистрирован "
                        + "(нет EssentialsX или другого экономического плагина).");
                return;
            }
            this.economy = rsp.getProvider();
            log.info("Vault hook подключён: " + economy.getName());
        } catch (Throwable t) {
            this.economy = null;
            log.warning("Не удалось получить Vault Economy: " + t.getMessage());
        }
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public void deposit(@NotNull Player player, double amount) {
        if (economy == null) {
            throw new IllegalStateException("Vault Economy недоступен");
        }
        OfflinePlayer op = player;
        var response = economy.depositPlayer(op, amount);
        if (!response.transactionSuccess()) {
            throw new IllegalStateException("Vault: deposit неуспешен — "
                    + response.errorMessage);
        }
    }
}