package ru.coolcontainers.block;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.api.events.CaseKeyChangeEvent;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.HologramConfig;
import ru.coolcontainers.key.KeyCache;
import ru.coolcontainers.util.MiniMessageUtil;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player строка «Ключей: N» под основной голограммой блока кейса.
 *
 *  - Одна TextDisplay на (block, player), видима только владельцу.
 *  - Обновление раз в секунду, только для блоков в радиусе VIEW_RADIUS.
 *  - Ключи читаются из KeyCache; если кэш пуст — warmUp запускается async.
 *  - Мгновенное обновление при CaseKeyChangeEvent (без ожидания тика).
 *  - Блоки без hologram.enabled или с пустыми lines — пропускаются.
 *  - Может быть временно подавлен (suppress) на время анимации кейса:
 *    строка не спавнится и существующая удаляется.
 *
 * Позиция строки ключей — ФИКСИРОВАННАЯ (KEY_LINE_OFFSET_Y от блока),
 * не зависит от высоты баннера. Это позволяет разнести их визуально:
 * баннер выше, ключи ниже, без слипания.
 */
public final class KeyLineHologramService implements Listener {

    public static final String METADATA_KEY = "case_key_holo";

    private static final double VIEW_RADIUS = 24.0;
    private static final double VIEW_RADIUS_SQ = VIEW_RADIUS * VIEW_RADIUS;

    /**
     * Высота центра строки «Ключей: N» над блоком.
     * Фиксирована и НЕ зависит от offset-y баннера — чтобы баннер и ключи
     * можно было разнести по высоте (например, баннер на 2.4, ключи на 1.4).
     */
    private static final double KEY_LINE_OFFSET_Y = 1.4;

    private final JavaPlugin plugin;
    private final CaseBlockService blocks;
    private final KeyCache keyCache;

    /** blockKey → (playerUuid → display). */
    private final Map<BlockKey, Map<UUID, TextDisplay>> displays = new HashMap<>();

    /** Блоки, у которых строка ключей временно скрыта (идёт анимация). */
    private final Set<BlockKey> suppressed = ConcurrentHashMap.newKeySet();

    private BukkitTask task;

    public KeyLineHologramService(@NotNull JavaPlugin plugin,
                                  @NotNull CaseBlockService blocks,
                                  @NotNull KeyCache keyCache) {
        this.plugin = plugin;
        this.blocks = blocks;
        this.keyCache = keyCache;
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        removeAll();
        suppressed.clear();
    }

    public void removeAll() {
        for (Map<UUID, TextDisplay> perPlayer : displays.values()) {
            for (TextDisplay d : perPlayer.values()) {
                if (d != null && !d.isDead()) d.remove();
            }
        }
        displays.clear();
    }

    public void removeAllForBlock(@NotNull BlockKey key) {
        suppressed.remove(key);
        Map<UUID, TextDisplay> perPlayer = displays.remove(key);
        if (perPlayer == null) return;
        for (TextDisplay d : perPlayer.values()) {
            if (d != null && !d.isDead()) d.remove();
        }
    }

    public void removeAllForPlayer(@NotNull UUID playerUuid) {
        for (Map<UUID, TextDisplay> perPlayer : displays.values()) {
            TextDisplay d = perPlayer.remove(playerUuid);
            if (d != null && !d.isDead()) d.remove();
        }
    }

    // ------------------------------------------------------------------
    //  Suppress API — временно скрыть строку на время анимации
    // ------------------------------------------------------------------

    /** Скрыть строку ключей на этом блоке до unsuppress(). */
    public void suppress(@NotNull BlockKey key) {
        suppressed.add(key);
        Map<UUID, TextDisplay> perPlayer = displays.remove(key);
        if (perPlayer == null) return;
        for (TextDisplay d : perPlayer.values()) {
            if (d != null && !d.isDead()) d.remove();
        }
    }

    public void unsuppress(@NotNull BlockKey key) {
        suppressed.remove(key);
    }

    public boolean isSuppressed(@NotNull BlockKey key) {
        return suppressed.contains(key);
    }

    // ------------------------------------------------------------------
    //  Слушатели
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        removeAllForPlayer(event.getPlayer().getUniqueId());
    }

    /** Мгновенное обновление текста над всеми блоками игрока при изменении ключей. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKeyChange(@NotNull CaseKeyChangeEvent event) {
        UUID playerUuid = event.getPlayerUuid();
        String caseId = event.getCaseId();
        Component text = MiniMessageUtil.parse(
                "<gray>Ключей: <white>" + event.getNewAmount() + "</white></gray>");

        for (Map.Entry<BlockKey, Map<UUID, TextDisplay>> e : displays.entrySet()) {
            if (suppressed.contains(e.getKey())) continue;

            TextDisplay d = e.getValue().get(playerUuid);
            if (d == null || d.isDead() || !d.isValid()) continue;

            CaseDefinition def = blocks.getCaseAtKey(e.getKey());
            if (def == null || !def.getId().equals(caseId)) continue;

            d.text(text);
        }
    }

    // ------------------------------------------------------------------
    //  Тик
    // ------------------------------------------------------------------

    private void tick() {
        if (!blocks.isActive()) return;

        // 1) Пройти по существующим: обновить текст / удалить неактуальные.
        Iterator<Map.Entry<BlockKey, Map<UUID, TextDisplay>>> bIt =
                displays.entrySet().iterator();
        while (bIt.hasNext()) {
            Map.Entry<BlockKey, Map<UUID, TextDisplay>> blockEntry = bIt.next();
            BlockKey key = blockEntry.getKey();
            Map<UUID, TextDisplay> perPlayer = blockEntry.getValue();

            // Подавленные блоки — сносим всё и не пересоздаём.
            if (suppressed.contains(key)) {
                removeAllIn(perPlayer);
                bIt.remove();
                continue;
            }

            CaseDefinition def = blocks.getCaseAtKey(key);
            HologramConfig holoCfg = def != null ? def.getHologram() : null;

            if (def == null || !holoCfg.enabled() || holoCfg.lines().isEmpty()) {
                removeAllIn(perPlayer);
                bIt.remove();
                continue;
            }

            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                removeAllIn(perPlayer);
                bIt.remove();
                continue;
            }

            Location blockCenter = new Location(world,
                    key.x() + 0.5, key.y() + 1.0, key.z() + 0.5);
            Location keyLoc = keyLineLocation(world, key);

            Iterator<Map.Entry<UUID, TextDisplay>> pIt = perPlayer.entrySet().iterator();
            while (pIt.hasNext()) {
                Map.Entry<UUID, TextDisplay> playerEntry = pIt.next();
                UUID playerUuid = playerEntry.getKey();
                TextDisplay d = playerEntry.getValue();

                Player player = Bukkit.getPlayer(playerUuid);
                if (player == null || !player.isOnline()
                        || !player.getWorld().equals(world)
                        || player.getLocation().distanceSquared(blockCenter) > VIEW_RADIUS_SQ) {
                    if (d != null && !d.isDead()) d.remove();
                    pIt.remove();
                    continue;
                }

                Component text = buildText(playerUuid, def.getId());
                if (d == null || d.isDead() || !d.isValid()) {
                    TextDisplay fresh = spawnFor(player, keyLoc, text);
                    if (fresh != null) playerEntry.setValue(fresh);
                } else {
                    d.text(text);
                    d.teleport(keyLoc);
                }
            }

            if (perPlayer.isEmpty()) bIt.remove();
        }

        // 2) Спавн новых — для каждого игрока найти блоки рядом.
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerUuid = player.getUniqueId();
            Location pLoc = player.getLocation();
            World pWorld = pLoc.getWorld();
            if (pWorld == null) continue;

            for (Map.Entry<BlockKey, String> e : blocks.snapshot().entrySet()) {
                BlockKey key = e.getKey();
                if (!key.world().equals(pWorld.getName())) continue;
                if (suppressed.contains(key)) continue;

                Location blockCenter = new Location(pWorld,
                        key.x() + 0.5, key.y() + 1.0, key.z() + 0.5);
                if (pLoc.distanceSquared(blockCenter) > VIEW_RADIUS_SQ) continue;

                CaseDefinition def = blocks.getCaseAtKey(key);
                if (def == null) continue;
                HologramConfig holoCfg = def.getHologram();
                if (!holoCfg.enabled() || holoCfg.lines().isEmpty()) continue;

                Map<UUID, TextDisplay> perPlayer = displays.get(key);
                if (perPlayer != null && perPlayer.containsKey(playerUuid)) continue;

                Location keyLoc = keyLineLocation(pWorld, key);
                Component text = buildText(playerUuid, def.getId());
                TextDisplay d = spawnFor(player, keyLoc, text);
                if (d != null) {
                    displays.computeIfAbsent(key, k -> new HashMap<>())
                            .put(playerUuid, d);
                }
            }
        }
    }

    /**
     * Позиция строки «Ключей: N» — ФИКСИРОВАННАЯ высота от блока.
     * Не зависит от {@code cfg.offsetY()} баннера. Баннер и ключи
     * разнесены визуально (например, баннер на 2.4, ключи на 1.4).
     */
    private static @NotNull Location keyLineLocation(@NotNull World world,
                                                     @NotNull BlockKey key) {
        return new Location(world,
                key.x() + 0.5,
                key.y() + KEY_LINE_OFFSET_Y,
                key.z() + 0.5);
    }

    private @NotNull Component buildText(@NotNull UUID playerUuid,
                                         @NotNull String caseId) {
        Optional<Integer> cached = keyCache.peek(playerUuid, caseId);
        if (cached.isEmpty()) {
            keyCache.warmUp(playerUuid, caseId);
        }
        int keys = cached.orElse(0);
        return MiniMessageUtil.parse("<gray>Ключей: <white>" + keys + "</white></gray>");
    }

    private void removeAllIn(@NotNull Map<UUID, TextDisplay> perPlayer) {
        for (TextDisplay d : perPlayer.values()) {
            if (d != null && !d.isDead()) d.remove();
        }
        perPlayer.clear();
    }

    private @Nullable TextDisplay spawnFor(@NotNull Player player,
                                           @NotNull Location loc,
                                           @NotNull Component text) {
        World world = loc.getWorld();
        if (world == null) return null;

        TextDisplay d = world.spawn(loc, TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setGravity(false);
            display.setViewRange(1.0f);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setVisibleByDefault(false);
            display.setMetadata(METADATA_KEY,
                    new FixedMetadataValue(plugin, player.getUniqueId().toString()));
        });

        player.showEntity(plugin, d);
        return d;
    }

    /** Удалить все case_key_holo в радиусе (при перерисовке чанка). */
    public int removeAllNear(@NotNull Location center, double radius) {
        int removed = 0;
        World world = center.getWorld();
        if (world == null) return 0;
        for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (e.hasMetadata(METADATA_KEY)) {
                e.remove();
                removed++;
            }
        }
        return removed;
    }
}