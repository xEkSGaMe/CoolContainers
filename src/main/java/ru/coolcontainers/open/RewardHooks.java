package ru.coolcontainers.open;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.cases.dto.Reward;

import java.util.List;
import java.util.UUID;

/**
 * Абстракция над интеграциями (LuckPerms / Vault / PlayerPoints / items / commands).
 * Реализация подключается в шаге 6.
 */
public interface RewardHooks {

    void giveLuckPerms(@NotNull UUID playerUuid, @NotNull Reward.LuckPermsEntry entry) throws Exception;

    void giveVault(@NotNull Player player, double amount) throws Exception;

    void givePlayerPoints(@NotNull UUID playerUuid, int amount) throws Exception;

    void giveItems(@NotNull Player player, @NotNull List<ItemStack> items) throws Exception;

    void runCommands(@NotNull Player player, @NotNull List<String> commands) throws Exception;
}