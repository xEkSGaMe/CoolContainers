package ru.coolcontainers.gui.editor;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.CoolContainersPlugin;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.gui.framework.GuiItem;
import ru.coolcontainers.gui.framework.GuiManager;

import java.util.ArrayList;
import java.util.List;

public final class CaseListGui {

    private final CoolContainersPlugin plugin;
    private final GuiManager gui;

    public CaseListGui(@NotNull CoolContainersPlugin plugin, @NotNull GuiManager gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    public void open(@NotNull Player admin) {
        List<CaseDefinition> defs = new ArrayList<>(plugin.cases().all());
        plugin.getLogger().info("[CaseListGui] открыт для " + admin.getName()
                + ", кейсов в реестре: " + defs.size()
                + ", id: " + plugin.cases().ids());

        int rows = Math.max(3, Math.min(6, (defs.size() + 8) / 9 + 1));

        Inventory inv = gui.create(admin, "editor_case_list", rows,
                "<dark_gray>Редактор кейсов</dark_gray>",
                holder -> {
                    // Фон
                    for (int i = 0; i < rows * 9; i++) {
                        holder.getInventory().setItem(i,
                                GuiItem.simple(Material.GRAY_STAINED_GLASS_PANE, " "));
                    }

                    // Пустой реестр
                    if (defs.isEmpty()) {
                        holder.getInventory().setItem(13, GuiItem.of(
                                Material.BARRIER,
                                "<red>Нет загруженных кейсов</red>",
                                List.of("<gray>Проверь папку <white>plugins/CoolContainers/cases/</white></gray>",
                                        "<gray>и лог при <white>/caseadmin reload</white></gray>")));
                    } else {
                        int slot = 0;
                        for (CaseDefinition def : defs) {
                            if (slot >= rows * 9 - 9) break; // оставляем нижний ряд под кнопки
                            int s = slot++;
                            holder.getInventory().setItem(s, GuiItem.of(
                                    Material.CHEST,
                                    "<white><bold>" + def.getId() + "</bold></white>",
                                    List.of(
                                            "<gray>Тип: <white>" + def.getType() + "</white></gray>",
                                            "<gray>Наград: <white>" + def.getRewards().size()
                                                    + "</white></gray>",
                                            "<yellow>ЛКМ — редактировать награды</yellow>")));
                            final String caseId = def.getId();
                            holder.setAction(s, p ->
                                    new RewardListGui(plugin, gui).open(p, caseId));
                        }
                    }

                    // Закрыть (последний слот)
                    int closeSlot = rows * 9 - 1;
                    holder.getInventory().setItem(closeSlot,
                            GuiItem.simple(Material.BARRIER, "<red>Закрыть</red>"));
                    holder.setAction(closeSlot, Player::closeInventory);
                });

        gui.open(admin, inv);
    }
}