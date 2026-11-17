package ru.coolcontainers.integrations;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.open.RewardHooks;

import java.util.List;
import java.util.UUID;

/**
 * Реальная реализация RewardHooks: LP + Vault + PP + items + commands.
 * Если плагин интеграции не подключён — соответствующий шаг падает с исключением,
 * что приводит к grant = FAILED (no-loss, админ увидит).
 */
public final class CompositeRewardHooks implements RewardHooks {

    private final JavaPlugin plugin;
    private final LuckPermsHook luckPerms;
    private final VaultHook vault;
    private final PlayerPointsHook playerPoints;

    public CompositeRewardHooks(@NotNull JavaPlugin plugin,
                                @NotNull LuckPermsHook luckPerms,
                                @NotNull VaultHook vault,
                                @NotNull PlayerPointsHook playerPoints) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        this.vault = vault;
        this.playerPoints = playerPoints;
    }

    @Override
    public void giveLuckPerms(@NotNull UUID playerUuid,
                              @NotNull Reward.LuckPermsEntry entry) throws Exception {
        if (!luckPerms.isAvailable()) {
            throw new IllegalStateException("LuckPerms недоступен");
        }
        luckPerms.grant(playerUuid, entry);
    }

    @Override
    public void giveVault(@NotNull Player player, double amount) throws Exception {
        if (!vault.isAvailable()) {
            throw new IllegalStateException("Vault Economy недоступен");
        }
        vault.deposit(player, amount);
    }

    @Override
    public void givePlayerPoints(@NotNull UUID playerUuid, int amount) throws Exception {
        if (!playerPoints.isAvailable()) {
            throw new IllegalStateException("PlayerPoints недоступен");
        }
        playerPoints.give(playerUuid, amount);
    }

    @Override
    public void giveItems(@NotNull Player player, @NotNull List<ItemStack> items) {
        for (ItemStack item : items) {
            ItemStack copy = item.clone();
            player.getInventory().addItem(copy).forEach((slot, leftover) ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    @Override
    public void runCommands(@NotNull Player player, @NotNull List<String> commands) {
        for (String cmd : commands) {
            String resolved = cmd.replace("{player}", player.getName());
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved));
        }
    }
}