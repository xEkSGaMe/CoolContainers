package ru.coolcontainers.gui.framework;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.util.MiniMessageUtil;

import java.util.ArrayList;
import java.util.List;

public final class GuiItem {

    private GuiItem() {}

    public static @NotNull ItemStack of(@NotNull Material material,
                                        @NotNull String name,
                                        @NotNull List<String> lore) {
        ItemStack st = new ItemStack(material);
        ItemMeta meta = st.getItemMeta();
        meta.displayName(MiniMessageUtil.parse(name));
        if (!lore.isEmpty()) {
            List<Component> comps = new ArrayList<>();
            for (String line : lore) comps.add(MiniMessageUtil.parse(line));
            meta.lore(comps);
        }
        st.setItemMeta(meta);
        return st;
    }

    public static @NotNull ItemStack of(@NotNull ItemStack base,
                                        @NotNull String name,
                                        @NotNull List<String> lore) {
        ItemStack st = base.clone();
        ItemMeta meta = st.getItemMeta();
        meta.displayName(MiniMessageUtil.parse(name));
        List<Component> comps = new ArrayList<>();
        for (String line : lore) comps.add(MiniMessageUtil.parse(line));
        meta.lore(comps);
        st.setItemMeta(meta);
        return st;
    }

    public static @NotNull ItemStack simple(@NotNull Material material, @NotNull String name) {
        return of(material, name, List.of());
    }
}