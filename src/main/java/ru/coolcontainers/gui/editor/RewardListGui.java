package ru.coolcontainers.gui.editor;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.CoolContainersPlugin;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.gui.framework.GuiItem;
import ru.coolcontainers.gui.framework.GuiManager;

import java.util.ArrayList;
import java.util.List;

public final class RewardListGui {

    private final CoolContainersPlugin plugin;
    private final GuiManager gui;

    public RewardListGui(@NotNull CoolContainersPlugin plugin, @NotNull GuiManager gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    /**
     * Работает по caseId — не держит устаревший CaseDefinition.
     * Каждый раз перечитывает актуальный def из реестра.
     */
    public void open(@NotNull Player admin, @NotNull String caseId) {
        CaseDefinition def = plugin.cases().get(caseId);
        if (def == null) {
            admin.sendMessage("§cКейс " + caseId + " не найден.");
            admin.closeInventory();
            return;
        }

        List<Reward> rewards = def.getRewards();
        int rows = 6;

        Inventory inv = gui.create(admin, "editor_reward_list", rows,
                "<dark_gray>Награды: <white>" + caseId + "</white></dark_gray>",
                holder -> {
                    for (int i = 0; i < rows * 9; i++) {
                        holder.getInventory().setItem(i,
                                GuiItem.simple(Material.GRAY_STAINED_GLASS_PANE, " "));
                    }

                    int slot = 0;
                    for (Reward r : rewards) {
                        if (slot >= 45) break;
                        int s = slot++;
                        ItemStack icon = r.getItems().isEmpty()
                                ? new ItemStack(Material.PAPER)
                                : r.getItems().get(0).clone();
                        holder.getInventory().setItem(s, GuiItem.of(
                                icon,
                                r.getDisplayName().isEmpty() ? r.getId() : r.getDisplayName(),
                                List.of(
                                        "<gray>id: <white>" + r.getId() + "</white></gray>",
                                        "<gray>шанс: <white>" + r.getChance() + "%</white></gray>",
                                        "<gray>amount: <white>" + r.getAmount() + "</white></gray>",
                                        "<yellow>ЛКМ — редактор</yellow>",
                                        "<red>Shift+ЛКМ — удалить</red>")));

                        // === Этап 10.3: ЛКМ — редактор, Shift+ЛКМ — удалить ===
                        final String rewardId = r.getId();
                        holder.setAction(s, p ->
                                new RewardEditorGui(plugin, gui).open(p, caseId, rewardId));
                        holder.setShiftAction(s, p -> {
                            deleteReward(caseId, rewardId);
                            open(p, caseId);
                        });
                    }

                    // Кнопка "Назад"
                    holder.getInventory().setItem(49,
                            GuiItem.simple(Material.ARROW, "<yellow>Назад</yellow>"));
                    holder.setAction(49, p -> new CaseListGui(plugin, gui).open(p));

                    // Кнопка "Добавить"
                    holder.getInventory().setItem(53, GuiItem.of(
                            Material.EMERALD,
                            "<green><bold>+ Добавить</bold></green>",
                            List.of("<gray>Создать награду на основе</gray>",
                                    "<gray>предмета в твоей руке</gray>")));
                    holder.setAction(53, p -> addReward(p, caseId));
                });

        gui.open(admin, inv);
    }

    private void addReward(@NotNull Player admin, @NotNull String caseId) {
        CaseDefinition current = plugin.cases().get(caseId);
        if (current == null) { admin.closeInventory(); return; }

        ItemStack hand = admin.getInventory().getItemInMainHand();
        List<ItemStack> items = new ArrayList<>();
        if (hand != null && !hand.getType().isAir()) {
            items.add(hand.clone());
        }

        Reward newR = Reward.builder("reward_" + System.currentTimeMillis() % 100000)
                .displayName("<gray>Новая награда</gray>")
                .chance(0.0)
                .amount(1)
                .items(items)
                .build();

        List<Reward> updated = new ArrayList<>(current.getRewards());
        updated.add(newR);
        CaseDefinition newDef = current.withRewards(updated);

        plugin.cases().register(newDef);
        plugin.saveCase(newDef);
        open(admin, caseId);
    }

    private void deleteReward(@NotNull String caseId, @NotNull String rewardId) {
        CaseDefinition current = plugin.cases().get(caseId);
        if (current == null) return;

        List<Reward> updated = new ArrayList<>(current.getRewards());
        updated.removeIf(r -> r.getId().equals(rewardId));

        CaseDefinition newDef = current.withRewards(updated);
        plugin.cases().register(newDef);
        plugin.saveCase(newDef);
    }
}