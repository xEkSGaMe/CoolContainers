package ru.coolcontainers.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Запрещает подбор дропов анимации (красные/зелёные красители).
 * Помечены metadata "cc_anim_drop" в CreeperSession.
 */
public final class AnimationDropListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(@NotNull EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getItem().hasMetadata("cc_anim_drop")) {
            event.setCancelled(true);
        }
    }
}