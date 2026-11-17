package ru.coolcontainers.block;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.cases.CaseRegistry;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.config.PluginConfig;
import ru.coolcontainers.db.repository.BlockRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Управление блоками кейсов на этом сервере.
 *
 * В памяти: Map<BlockKey, caseId>.
 * В БД: cc_case_blocks (server_id, world, x, y, z, case_id).
 * Голограммы: HologramService (базовая) + KeyLineHologramService (per-player строка ключей).
 *
 * Если server-id != blocks.host-server-id — блоки не грузятся и не создаются.
 */
public final class CaseBlockService implements Listener {

    private final JavaPlugin plugin;
    private final Logger log;
    private final BlockRepository repo;
    private final HologramService holo;
    private final CaseRegistry registry;
    private final PluginConfig config;

    private final Map<BlockKey, String> blocks = new LinkedHashMap<>();
    private final boolean active;

    /** Устанавливается из CoolContainersPlugin после создания обоих сервисов. */
    private KeyLineHologramService keyLineHologramService;

    public CaseBlockService(@NotNull JavaPlugin plugin,
                            @NotNull BlockRepository repo,
                            @NotNull HologramService holo,
                            @NotNull CaseRegistry registry,
                            @NotNull PluginConfig config) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.repo = repo;
        this.holo = holo;
        this.registry = registry;
        this.config = config;
        this.active = config.getServerId().equals(config.getBlocksHostServerId());
        if (!active) {
            log.info("Блоки кейсов: server-id != host-server-id — блоки отключены.");
        }
    }

    public void setKeyLineHologramService(@NotNull KeyLineHologramService s) {
        this.keyLineHologramService = s;
    }

    public boolean isActive() {
        return active;
    }

    /** Загрузить блоки из БД и отрисовать голограммы в загруженных мирах. */
    public void load() {
        if (!active) return;
        blocks.clear();
        try {
            var rows = repo.findAllByServer(config.getServerId());
            for (var row : rows) {
                BlockKey key = new BlockKey(row.world(), row.x(), row.y(), row.z());
                blocks.put(key, row.caseId());
            }
            log.info("Загружено блоков кейсов: " + blocks.size());
            spawnAllHolograms();
        } catch (SQLException e) {
            log.warning("Не удалось загрузить блоки кейсов: " + e.getMessage());
        }
    }

    /** Перерисовать все голограммы (после reload). */
    public void refreshAllHolograms() {
        if (!active) return;
        for (World w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (e.hasMetadata(HologramService.METADATA_KEY)
                        || e.hasMetadata(KeyLineHologramService.METADATA_KEY)) {
                    e.remove();
                }
            }
        }
        if (keyLineHologramService != null) {
            keyLineHologramService.removeAll();
        }
        spawnAllHolograms();
    }

    private void spawnAllHolograms() {
        for (var entry : blocks.entrySet()) {
            BlockKey key = entry.getKey();
            CaseDefinition def = registry.get(entry.getValue());
            World world = Bukkit.getWorld(key.world());
            if (world == null) continue;
            if (def == null) {
                log.warning("Блок " + key + " ссылается на отсутствующий кейс " + entry.getValue());
                continue;
            }
            if (!world.isChunkLoaded(key.chunkX(), key.chunkZ())) continue;
            holo.spawn(key, def, world);
        }
    }

    /** Привязка блока. */
    public void bind(@NotNull Location location, @NotNull String caseId) throws SQLException {
        if (!active) return;
        BlockKey key = BlockKey.of(location);
        repo.bind(config.getServerId(), key.world(), key.x(), key.y(), key.z(), caseId);
        blocks.put(key, caseId);

        holo.removeNear(new Location(location.getWorld(), key.x(), key.y(), key.z()));
        CaseDefinition def = registry.get(caseId);
        if (def != null && location.getWorld().isChunkLoaded(key.chunkX(), key.chunkZ())) {
            holo.spawn(key, def, location.getWorld());
        }
    }

    /** Отвязка блока. */
    public void unbind(@NotNull Location location) throws SQLException {
        if (!active) return;
        BlockKey key = BlockKey.of(location);
        repo.unbind(config.getServerId(), key.world(), key.x(), key.y(), key.z());
        blocks.remove(key);
        holo.removeNear(new Location(location.getWorld(), key.x(), key.y(), key.z()));
        if (keyLineHologramService != null) {
            keyLineHologramService.removeAllForBlock(key);
        }
    }

    /**
     * Удалить все блоки кейса (при /caseadmin delete) на текущем сервере.
     * Чужие серверы не трогаем — фильтр по server_id внутри repo.unbindAll.
     */
    public void unbindAll(@NotNull String caseId) throws SQLException {
        if (!active) return;
        repo.unbindAll(config.getServerId(), caseId);

        List<BlockKey> toRemove = new ArrayList<>();
        for (var e : blocks.entrySet()) {
            if (e.getValue().equals(caseId)) {
                toRemove.add(e.getKey());
                World w = Bukkit.getWorld(e.getKey().world());
                if (w != null) {
                    holo.removeNear(new Location(w,
                            e.getKey().x(), e.getKey().y(), e.getKey().z()));
                }
            }
        }
        toRemove.forEach(blocks::remove);

        if (keyLineHologramService != null) {
            for (BlockKey key : toRemove) {
                keyLineHologramService.removeAllForBlock(key);
            }
        }
    }

    public @Nullable String getCaseAt(@NotNull Location location) {
        if (!active) return null;
        if (location.getWorld() == null) return null;
        if (!location.getWorld().getName().equals(config.getBlocksAllowedWorld())) return null;
        return blocks.get(BlockKey.of(location));
    }

    /** CaseDefinition по BlockKey напрямую (без мира/Location). */
    public @Nullable CaseDefinition getCaseAtKey(@NotNull BlockKey key) {
        if (!active) return null;
        String caseId = blocks.get(key);
        if (caseId == null) return null;
        return registry.get(caseId);
    }

    public boolean isCaseBlock(@NotNull Location location) {
        return getCaseAt(location) != null;
    }

    public @NotNull Map<BlockKey, String> snapshot() {
        return Collections.unmodifiableMap(blocks);
    }

    /** Блоки, попадающие в указанный чанк (для ChunkLoadEvent). */
    public @NotNull List<BlockKey> blocksInChunk(@NotNull Chunk chunk) {
        List<BlockKey> out = new ArrayList<>();
        for (BlockKey key : blocks.keySet()) {
            if (key.world().equals(chunk.getWorld().getName())
                    && key.chunkX() == chunk.getX()
                    && key.chunkZ() == chunk.getZ()) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * ChunkLoadEvent: заново отрисовать голограммы этого чанка.
     *
     * P2-2: убран вызов keyLineHologramService.removeAllNear(center, 32.0).
     *
     * Причина: радиус 32 — это ~2 чанка вокруг. Он сносил per-player строки
     * ключей у блоков в СОСЕДНИХ чанках. Эти строки восстанавливались только
     * в KeyLineHologramService.tick() для игроков в VIEW_RADIUS = 24 — то есть
     * блоки на расстоянии 24..32 от игрока оставались без строки ключей.
     *
     * Key-line голограммы помечены setPersistent(false), не переживают unload
     * чанка, и при перезагрузке их просто нет — удалять нечего. Если что-то
     * всё-таки уцелело (старый jar, кастомная сборка), это снесёт
     * KeyLineHologramService.tick() при первом же проходе через
     * `if (d == null || d.isDead() || !d.isValid())` — пересоздаст.
     *
     * Базовые голограммы (holo.removeAllInChunk) удаляются на всякий случай —
     * это дешёвая страховка против легаси-сохранений, не влияет на соседей.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(@NotNull ChunkLoadEvent event) {
        if (!active) return;
        Chunk chunk = event.getChunk();
        holo.removeAllInChunk(chunk);

        for (BlockKey key : blocksInChunk(chunk)) {
            CaseDefinition def = registry.get(blocks.get(key));
            if (def == null) continue;
            holo.spawn(key, def, chunk.getWorld());
        }
    }
}