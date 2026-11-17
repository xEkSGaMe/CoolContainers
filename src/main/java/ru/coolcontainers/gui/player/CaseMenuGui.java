package ru.coolcontainers.gui.player;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.CoolContainersPlugin;
import ru.coolcontainers.animations.AnimationService;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.block.BlockKey;
import ru.coolcontainers.block.BlockLockService;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.CaseType;
import ru.coolcontainers.gui.framework.GuiItem;
import ru.coolcontainers.gui.framework.GuiManager;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class CaseMenuGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CoolContainersPlugin plugin;
    private final GuiManager gui;
    private final JavaPlugin javaPlugin;

    public CaseMenuGui(@NotNull CoolContainersPlugin plugin, @NotNull GuiManager gui) {
        this.plugin = plugin;
        this.gui = gui;
        this.javaPlugin = plugin;
    }

    public void open(@NotNull Player player,
                     @NotNull CaseDefinition def,
                     @NotNull Block block) {
        UUID uuid = player.getUniqueId();
        String caseId = def.getId();

        plugin.dbExecutor().supply(() -> plugin.keyService().getAmount(uuid, caseId))
                .whenComplete((keys, ex) -> plugin.dbExecutor().onMain(() -> {
                    if (!player.isOnline()) return;
                    if (ex != null) {
                        plugin.getLogger().warning("CaseMenuGui: keys load failed: "
                                + ex.getMessage());
                    }
                    int amount = (ex != null || keys == null) ? 0 : keys;
                    openWithKeys(player, def, block, amount);
                }));
    }

    private void openWithKeys(@NotNull Player player,
                              @NotNull CaseDefinition def,
                              @NotNull Block block,
                              int keysAmount) {
        final BlockKey blockKey = BlockKey.of(block);
        final String title = "<!italic>" + def.getDisplayName();

        Inventory inv = gui.create(player, "case_menu", 3,
                title,
                holder -> {
                    for (int i = 0; i < 27; i++) {
                        holder.getInventory().setItem(i,
                                GuiItem.simple(Material.GRAY_STAINED_GLASS_PANE, " "));
                    }

                    boolean hasKeys = keysAmount > 0;
                    Material icon = hasKeys ? Material.CHEST : Material.BARRIER;

                    String description = def.getDescription();
                    if (description == null || description.isBlank()) {
                        description = defaultDescription(def.getType());
                    }

                    List<String> lore = new ArrayList<>();
                    if (!description.isBlank()) {
                        lore.add("<!italic>" + description);
                        lore.add("");
                    }
                    lore.add("<!italic><gray>Ключей: <white>" + keysAmount
                            + "</white></gray>");
                    lore.add("");
                    if (hasKeys) {
                        lore.add("<!italic><yellow>Нажми чтобы открыть!</yellow>");
                    } else {
                        lore.add("<!italic><red>Нужен хотя бы один ключ</red>");
                    }

                    holder.getInventory().setItem(13, GuiItem.of(
                            icon,
                            "<!italic>" + def.getDisplayName(),
                            lore));

                    if (hasKeys) {
                        holder.setAction(13, p -> {
                            p.closeInventory();
                            tryOpen(p, def, block, blockKey);
                        });
                    }
                });

        gui.open(player, inv);
    }

    private static @NotNull String defaultDescription(@NotNull CaseType type) {
        return switch (type) {
            case DONATE -> "<gold>Испытай удачу и получи донат-привилегию!</gold>";
            case SMALL_DONATE -> "<yellow>Отличный старт для новичка.</yellow>";
            case CURRENCY -> "<yellow>Монеты для твоих приключений!</yellow>";
            case RUBY -> "<red>Рубины — твёрдая валюта сервера.</red>";
            case TREASURE_SMALL -> "<gray>Немного сокровищ никому не помешает.</gray>";
            case TREASURE_MEDIUM -> "<gold>Серьёзный улов ждёт тебя!</gold>";
            case TREASURE_LARGE -> "<dark_purple>Сокровища для настоящих искателей!</dark_purple>";
            case CUSTOM -> "<white>Особый кейс.</white>";
        };
    }

    private void tryOpen(@NotNull Player player,
                         @NotNull CaseDefinition def,
                         @NotNull Block block,
                         @NotNull BlockKey key) {
        BlockLockService locks = plugin.blockLockService();
        AnimationService anims = plugin.animationService();

        if (locks.isAnimating(key) || locks.isOnCooldown(key)) {
            player.sendMessage(MM.deserialize("<red>Этот блок сейчас занят.</red>"));
            return;
        }
        if (anims.isAtMaxConcurrent()) {
            player.sendMessage(MM.deserialize(
                    "<red>Слишком много открытий, попробуй позже.</red>"));
            return;
        }
        if (!locks.lockForAnimation(key)) return;

        plugin.caseOpenService().startOpenAsync(player, def.getId(), block.getLocation())
                .whenComplete((openingId, ex) -> plugin.dbExecutor().onMain(() -> {
                    if (!player.isOnline()) {
                        locks.forceUnlock(key);
                        return;
                    }
                    if (ex != null) {
                        plugin.getLogger().warning("startOpenAsync упал: " + ex.getMessage());
                        locks.forceUnlock(key);
                        player.sendMessage(MM.deserialize(
                                "<red>Не удалось открыть: внутренняя ошибка.</red>"));
                        return;
                    }
                    if (openingId == null) {
                        locks.forceUnlock(key);
                        player.sendMessage(MM.deserialize(
                                "<red>Не удалось открыть: нет ключа или отмена.</red>"));
                        return;
                    }

                    // P2-5: если anims.play бросит (например, конструктор сессии),
                    // opening остался бы в ANIMATING, ключ списан, блок залочен.
                    // Ловим и откатываем безопасно: markRefunded → addKeys.
                    try {
                        anims.play(openingId, player, def, block.getLocation(), key);
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.SEVERE,
                                "anims.play failed for " + openingId, t);
                        refundAfterPlayFailure(openingId, player, def.getId(), key);
                    }
                }));
    }

    /**
     * Откат после провала anims.play.
     * Порядок markRefunded → addKeys — та же защита от дюпа, что и в
     * AnimationService.refund.
     */
    private void refundAfterPlayFailure(@NotNull UUID openingId,
                                        @NotNull Player player,
                                        @NotNull String caseId,
                                        @NotNull BlockKey key) {
        plugin.blockLockService().forceUnlock(key);

        plugin.dbExecutor().run(() -> {
            boolean marked;
            try {
                marked = plugin.openingRepository().markRefunded(openingId);
            } catch (SQLException e) {
                plugin.getLogger().warning("refundAfterPlayFailure: markRefunded "
                        + openingId + ": " + e.getMessage()
                        + " — opening оставлен для recovery");
                return;
            }
            if (!marked) {
                plugin.getLogger().info("refundAfterPlayFailure: opening " + openingId
                        + " уже терминальный — ключ не возвращён");
                return;
            }
            try {
                plugin.keyService().addKeys(player.getUniqueId(), caseId,
                        1, KeyReason.REFUND_ABORTED, null);
            } catch (SQLException e) {
                plugin.getLogger().warning("refundAfterPlayFailure: opening " + openingId
                        + " помечен REFUNDED, но addKeys упал: " + e.getMessage()
                        + " — верни ключ вручную игроку " + player.getUniqueId()
                        + " case=" + caseId);
            }
        });

        if (player.isOnline()) {
            player.sendMessage(MM.deserialize(
                    "<red>Ошибка запуска анимации. Ключ возвращён.</red>"));
        }
    }
}