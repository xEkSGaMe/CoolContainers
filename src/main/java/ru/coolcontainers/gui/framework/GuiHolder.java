package ru.coolcontainers.gui.framework;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * InventoryHolder для всех наших GUI.
 * Хранит карты слот → действие (обычный клик / shift-клик).
 */
public final class GuiHolder implements InventoryHolder {

    @FunctionalInterface
    public interface SlotAction {
        void run(@NotNull org.bukkit.entity.Player player);
    }

    private final UUID owner;
    private final String guiId;
    private final Map<Integer, SlotAction> actions = new HashMap<>();
    // === Этап 10 ===
    private final Map<Integer, SlotAction> shiftActions = new HashMap<>();

    private Inventory inventory;

    public GuiHolder(@NotNull UUID owner, @NotNull String guiId) {
        this.owner = owner;
        this.guiId = guiId;
    }

    public @NotNull UUID owner() { return owner; }
    public @NotNull String guiId() { return guiId; }

    public void setAction(int slot, @NotNull SlotAction action) {
        actions.put(slot, action);
    }

    public @Nullable SlotAction getAction(int slot) {
        return actions.get(slot);
    }

    public @NotNull Map<Integer, SlotAction> actions() {
        return actions;
    }

    // === Этап 10 ===
    public void setShiftAction(int slot, @NotNull SlotAction action) {
        shiftActions.put(slot, action);
    }

    public @Nullable SlotAction getShiftAction(int slot) {
        return shiftActions.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Inventory not set");
        return inventory;
    }

    public void setInventory(@NotNull Inventory inventory) {
        this.inventory = inventory;
    }
}