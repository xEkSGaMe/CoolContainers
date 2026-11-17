package ru.coolcontainers.gui.framework;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.util.MiniMessageUtil;

/**
 * Обёртка для создания инвентаря с GuiHolder.
 */
public final class GuiManager {

    private final JavaPlugin plugin;

    public GuiManager(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public @NotNull Inventory create(@NotNull Player player,
                                     @NotNull String guiId,
                                     int rows,
                                     @NotNull String title,
                                     @NotNull java.util.function.Consumer<GuiHolder> filler) {
        if (rows < 1 || rows > 6) rows = 3;
        GuiHolder holder = new GuiHolder(player.getUniqueId(), guiId);
        Component titleComp = MiniMessageUtil.parse(title);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, titleComp);
        holder.setInventory(inv);
        filler.accept(holder);
        return inv;
    }

    public void open(@NotNull Player player, @NotNull Inventory inventory) {
        player.openInventory(inventory);
    }
}