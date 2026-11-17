package ru.coolcontainers.gui.input;

import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AnvilInput {

    private final JavaPlugin plugin;

    public AnvilInput(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // === patch #4: не режем MiniMessage-теги посередине ===
    private static String safeTitle(String raw) {
        if (raw == null) return "";
        String plain = raw.replaceAll("<[^>]+>", "");
        return plain.length() > 32 ? plain.substring(0, 32) : plain;
    }

    /**
     * Запросить строку. onResult принимает текст или null, если игрок закрыл.
     *
     * === Этап 10.4 ===
     * onClick СРАЗУ вызывает once.accept(text) синхронно, не полагаясь на
     * ResponseAction.run (тот срабатывает после закрытия наковальни).
     * handled защёлкивается внутри runTask — через тик, поэтому правильный
     * порядок (onClick → onClose) сохраняется.
     */
    public void promptString(@NotNull Player player,
                             @NotNull String title,
                             @NotNull String initialText,
                             @NotNull Consumer<@Nullable String> onResult) {
        final AtomicBoolean handled = new AtomicBoolean(false);

        final Consumer<@Nullable String> once = raw ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (handled.compareAndSet(false, true)) {
                        onResult.accept(raw);
                    }
                });

        new AnvilGUI.Builder()
                .plugin(plugin)
                .title(safeTitle(title))
                .itemLeft(new ItemStack(Material.PAPER))
                .text(initialText == null ? "" : initialText)
                .onClick((slot, state) -> {
                    if (slot != AnvilGUI.Slot.OUTPUT) return Collections.emptyList();
                    String text = state.getText();
                    once.accept(text);
                    return Collections.singletonList(AnvilGUI.ResponseAction.close());
                })
                .onClose(state -> once.accept(null))
                .open(player);
    }

    /** Запросить int. onResult принимает число или null, если игрок закрыл или ввёл мусор. */
    public void promptInt(@NotNull Player player,
                          @NotNull String title,
                          int initialValue,
                          @NotNull Consumer<@Nullable Integer> onResult) {
        promptString(player, title, String.valueOf(initialValue), raw -> {
            if (raw == null) { onResult.accept(null); return; }
            try {
                onResult.accept(Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                onResult.accept(null);
            }
        });
    }

    /** Запросить double. */
    public void promptDouble(@NotNull Player player,
                             @NotNull String title,
                             double initialValue,
                             @NotNull Consumer<@Nullable Double> onResult) {
        promptString(player, title, String.valueOf(initialValue), raw -> {
            if (raw == null) { onResult.accept(null); return; }
            try {
                onResult.accept(Double.parseDouble(raw.trim()));
            } catch (NumberFormatException e) {
                onResult.accept(null);
            }
        });
    }
}