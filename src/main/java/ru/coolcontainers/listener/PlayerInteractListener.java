package ru.coolcontainers.listener;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.CoolContainersPlugin;
import ru.coolcontainers.animations.AnimationService;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.config.PluginConfig;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerInteractListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CoolContainersPlugin plugin;
    private final PluginConfig config;

    /**
     * P2-7: дедуп между HAND и OFF_HAND в рамках одного тика.
     *
     * Проблема: если main hand пустой, а off-hand содержит предмет, Bukkit
     * может фаерить PlayerInteractEvent с getHand() == OFF_HAND — старый код
     * отбрасывал его (`!= HAND`), и кейс не открывался.
     *
     * Решение: обрабатываем оба hand'а, но игрока помечаем на 1 тик, чтобы
     * одиночный клик не открыл меню дважды (если Bukkit всё-таки фаерит
     * оба event'а).
     */
    private final Set<UUID> recentlyHandled = ConcurrentHashMap.newKeySet();

    public PlayerInteractListener(@NotNull CoolContainersPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.config();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        // getHand() == null бывает на LEFT_CLICK_AIR и похожих — просто отсеиваем.
        if (event.getHand() == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        // --- setblock pending ---
        if (player.hasPermission("cases.admin")
                && plugin.caseAdminCommand().hasPendingSetBlock(player)
                && block.getWorld().getName().equals(config.getBlocksAllowedWorld())) {
            event.setCancelled(true);
            String caseId = plugin.caseAdminCommand().consumePendingSetBlock(player);
            if (caseId == null) return;
            try {
                plugin.caseBlockService().bind(block.getLocation(), caseId);
                player.sendMessage(MM.deserialize("<green>Блок привязан к кейсу <white>"
                        + caseId + "</white>.</green>"));
            } catch (Exception e) {
                player.sendMessage(MM.deserialize("<red>Ошибка привязки: " + e.getMessage() + "</red>"));
            }
            return;
        }

        // --- обычный клик по блоку кейса ---
        if (!block.getWorld().getName().equals(config.getBlocksAllowedWorld())) return;
        String caseId = plugin.caseBlockService().getCaseAt(block.getLocation());
        if (caseId == null) return;

        event.setCancelled(true);

        // Дедуп: за один тик — только один обработчик на игрока.
        UUID uuid = player.getUniqueId();
        if (!recentlyHandled.add(uuid)) return;
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> recentlyHandled.remove(uuid), 1L);

        CaseDefinition def = plugin.cases().get(caseId);
        if (def == null) return;

        if (!player.hasPermission(def.getPermission())) {
            player.sendMessage(plugin.messages().prefixed("general.no-permission", java.util.Map.of()));
            return;
        }

        plugin.caseMenuGui().open(player, def, block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        AnimationService anims = plugin.animationService();
        if (!anims.isAnimationEntity(entity)) return;
        if (anims.handleEntityClick(event.getPlayer(), entity)) {
            event.setCancelled(true);
        }
    }

    /**
     * Перехват атаки по сущности анимации. Работает и в выживании, и в креативе,
     * потому что криперы теперь не invulnerable (см. CreeperSession.spawnOne).
     * LOWEST — до того, как сервер успеет применить свой invulnerability/noDamage
     * check.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamageByPlayer(@NotNull EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        Entity entity = event.getEntity();
        AnimationService anims = plugin.animationService();
        if (!anims.isAnimationEntity(entity)) return;
        if (anims.handleEntityClick(p, entity)) {
            event.setCancelled(true);
        }
    }

    /**
     * Страховка: любые другие источники урона по сущностям анимации — отменить.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnyDamage(@NotNull EntityDamageEvent event) {
        if (plugin.animationService().isAnimationEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}