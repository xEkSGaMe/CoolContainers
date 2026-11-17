package ru.coolcontainers.gui.framework;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Единственный Listener для всех GUI.
 * Гарантирует: shift-click, number keys, swap, drag, drop outside — отменены.
 * Открывает действия только из GuiHolder.
 */
public final class GuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(@NotNull InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof GuiHolder gui)) return;

        // Запрещаем любые перемещения предметов по умолчанию.
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();

        // Клик по верхнему инвентарю (нашему) — слот валиден.
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(event.getInventory())) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        // Запрещаем двойной клик (собирает стак со всего инвентаря).
        if (event.getClick() == ClickType.DOUBLE_CLICK) return;

        // === Этап 10.2: учёт shift-click через event.isShiftClick() ===
        GuiHolder.SlotAction action;
        if (event.isShiftClick()) {
            action = gui.getShiftAction(slot);
            if (action == null) {
                action = gui.getAction(slot);
            }
        } else {
            action = gui.getAction(slot);
        }

        if (action != null) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("CoolContainers");
            if (plugin == null) return;
            final GuiHolder.SlotAction finalAction = action;
            // Отложенный вызов — Bukkit не любит открывать новый инвентарь в click-event.
            Bukkit.getScheduler().runTask(plugin, () -> finalAction.run(player));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(@NotNull InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClose(@NotNull InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof GuiHolder)) return;

        Player player = (Player) event.getPlayer();
        // Если на курсоре что-то осталось — вернём в инвентарь.
        ItemStack onCursor = player.getItemOnCursor();
        if (onCursor != null && !onCursor.getType().isAir()) {
            player.setItemOnCursor(null);
            var leftover = player.getInventory().addItem(onCursor);
            leftover.forEach((i, item) ->
                    player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
    }

    /** Хелпер для других частей кода: является ли инвентарь нашим GUI. */
    public static boolean isGui(@NotNull Inventory inventory) {
        return inventory.getHolder() instanceof GuiHolder;
    }

    /** Хелпер: тип инвентаря — наш GUI игрока. */
    public static boolean isGuiFor(@NotNull Inventory inventory, @NotNull Player player) {
        if (!(inventory.getHolder() instanceof GuiHolder gui)) return false;
        return gui.owner().equals(player.getUniqueId());
    }

    /** Хелпер: check InventoryType == CHEST, чтобы не путать с наковальней. */
    public static boolean isChestGui(@NotNull Inventory inventory) {
        return inventory.getType() == InventoryType.CHEST
                && inventory.getHolder() instanceof GuiHolder;
    }
}