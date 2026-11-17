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
import ru.coolcontainers.gui.input.AnvilInput;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class RewardEditorGui {

    private final CoolContainersPlugin plugin;
    private final GuiManager gui;

    public RewardEditorGui(@NotNull CoolContainersPlugin plugin, @NotNull GuiManager gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    public void open(@NotNull Player admin,
                     @NotNull String caseId,
                     @NotNull String rewardId) {
        CaseDefinition def = plugin.cases().get(caseId);
        if (def == null) {
            admin.sendMessage("§cКейс " + caseId + " не найден.");
            admin.closeInventory();
            return;
        }
        Reward reward = def.getRewards().stream()
                .filter(r -> r.getId().equals(rewardId))
                .findFirst().orElse(null);
        if (reward == null) {
            admin.sendMessage("§cНаграда " + rewardId + " не найдена.");
            new RewardListGui(plugin, gui).open(admin, caseId);
            return;
        }

        Inventory inv = gui.create(admin, "editor_reward", 3,
                "<dark_gray>Награда: <white>" + rewardId + "</white></dark_gray>",
                holder -> {
                    for (int i = 0; i < 27; i++) {
                        holder.getInventory().setItem(i,
                                GuiItem.simple(Material.GRAY_STAINED_GLASS_PANE, " "));
                    }

                    // Показать предмет (или PAPER)
                    ItemStack icon = reward.getItems().isEmpty()
                            ? new ItemStack(Material.PAPER)
                            : reward.getItems().get(0).clone();
                    holder.getInventory().setItem(4, GuiItem.of(icon,
                            reward.getDisplayName().isEmpty()
                                    ? reward.getId() : reward.getDisplayName(),
                            List.of("<gray>id: <white>" + reward.getId() + "</white></gray>")));

                    // Шанс
                    holder.getInventory().setItem(10, GuiItem.of(Material.GOLD_INGOT,
                            "<yellow>Шанс</yellow>",
                            List.of("<gray>Текущий: <white>" + reward.getChance()
                                            + "%</white></gray>",
                                    "<yellow>ЛКМ — изменить</yellow>")));
                    holder.setAction(10, p -> new AnvilInput(plugin).promptDouble(
                            p, "Шанс %", reward.getChance(), value -> {
                                if (value == null) { reopen(p, caseId, rewardId); return; }
                                replace(caseId, rewardId, b -> b.chance(value));
                                reopen(p, caseId, rewardId);
                            }));

                    // Количество
                    holder.getInventory().setItem(11, GuiItem.of(Material.DIAMOND,
                            "<aqua>Количество</aqua>",
                            List.of("<gray>Текущее: <white>" + reward.getAmount()
                                            + "</white></gray>",
                                    "<yellow>ЛКМ — изменить</yellow>")));
                    holder.setAction(11, p -> new AnvilInput(plugin).promptInt(
                            p, "Amount", reward.getAmount(), value -> {
                                if (value == null) { reopen(p, caseId, rewardId); return; }
                                if (value <= 0) {
                                    p.sendMessage("§cAmount должен быть > 0.");
                                    reopen(p, caseId, rewardId);
                                    return;
                                }
                                replace(caseId, rewardId, b -> b.amount(value));
                                reopen(p, caseId, rewardId);
                            }));

                    // === patch #5: Display name с поддержкой MiniMessage ===
                    String rawName = reward.getDisplayName();
                    holder.getInventory().setItem(12, GuiItem.of(Material.NAME_TAG,
                            "<white>Название</white>",
                            List.of("<gray>Текущее: " + rawName + "</gray>",
                                    "<gray>Сырой ввод (MiniMessage): <white>" + rawName
                                            + "</white></gray>",
                                    "<yellow>ЛКМ — изменить</yellow>",
                                    "<dark_gray>Поддерживаются теги: &lt;green&gt;, "
                                            + "&lt;gradient:...&gt;</dark_gray>")));
                    holder.setAction(12, p -> new AnvilInput(plugin).promptString(
                            p, "Название (MiniMessage)", rawName, text -> {
                                if (text == null) { reopen(p, caseId, rewardId); return; }
                                replace(caseId, rewardId, b -> b.displayName(text));
                                reopen(p, caseId, rewardId);
                            }));

                    // Назад
                    holder.getInventory().setItem(22,
                            GuiItem.simple(Material.ARROW, "<yellow>Назад</yellow>"));
                    holder.setAction(22, p ->
                            new RewardListGui(plugin, gui).open(p, caseId));
                });

        gui.open(admin, inv);
    }

    /**
     * Перечитать свежий def/reward из реестра и переоткрыть GUI.
     * Если награда была удалена — открываем список наград.
     */
    private void reopen(@NotNull Player p,
                        @NotNull String caseId,
                        @NotNull String rewardId) {
        CaseDefinition fresh = plugin.cases().get(caseId);
        if (fresh == null) { p.closeInventory(); return; }
        boolean exists = fresh.getRewards().stream()
                .anyMatch(r -> r.getId().equals(rewardId));
        if (!exists) {
            new RewardListGui(plugin, gui).open(p, caseId);
            return;
        }
        open(p, caseId, rewardId);
    }

    /**
     * Заменяет награду по caseId+rewardId, читая актуальный CaseDefinition из реестра.
     */
    private void replace(@NotNull String caseId,
                         @NotNull String rewardId,
                         @NotNull Consumer<Reward.Builder> mutation) {
        CaseDefinition current = plugin.cases().get(caseId);
        if (current == null) return;

        int idx = -1;
        for (int i = 0; i < current.getRewards().size(); i++) {
            if (current.getRewards().get(i).getId().equals(rewardId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;

        Reward old = current.getRewards().get(idx);
        Reward.Builder b = Reward.builder(old.getId())
                .displayName(old.getDisplayName())
                .chance(old.getChance())
                .amount(old.getAmount())
                .items(old.getItems())
                .commands(old.getCommands())
                .broadcast(old.getBroadcast())
                .luckPerms(old.getLuckPerms())
                .vault(old.getVault())
                .playerPoints(old.getPlayerPoints());
        mutation.accept(b);
        Reward newR = b.build();

        List<Reward> updated = new ArrayList<>(current.getRewards());
        updated.set(idx, newR);

        CaseDefinition newDef = current.withRewards(updated);
        plugin.cases().register(newDef);
        plugin.saveCase(newDef);
    }

    // stripMini удалён (patch #5).
}