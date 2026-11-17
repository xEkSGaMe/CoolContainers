package ru.coolcontainers.block;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.HologramConfig;
import ru.coolcontainers.util.MiniMessageUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Голограммы над блоками кейсов на нативных TextDisplay.
 *
 * Ключевые моменты:
 *  - Каждая голограмма помечается metadata "case_holo".
 *  - При ChunkLoadEvent сначала чистим все case_holo в чанке (защита от дублей),
 *    затем CaseBlockService заново их отрисует.
 *  - setPersistent(false) — голограммы не сохраняются на диск.
 */
public final class HologramService {

    public static final String METADATA_KEY = "case_holo";

    private final JavaPlugin plugin;

    public HologramService(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Создать голограмму над блоком. Возвращает сущность или null, если выключено. */
    public @Nullable TextDisplay spawn(@NotNull BlockKey key,
                                       @NotNull CaseDefinition def,
                                       @NotNull World world) {
        HologramConfig cfg = def.getHologram();
        if (!cfg.enabled() || cfg.lines().isEmpty()) return null;

        Location loc = key.toLocation(world).add(0, cfg.offsetY(), 0);

        Component text = buildText(cfg);

        return world.spawn(loc, TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setGravity(false);
            display.setViewRange(1.0f);
            display.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, true));
        });
    }

    /** Удалить все case_holo в чанке. */
    public int removeAllInChunk(@NotNull Chunk chunk) {
        int removed = 0;
        for (Entity e : chunk.getEntities()) {
            if (e.hasMetadata(METADATA_KEY)) {
                e.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Удалить голограмму, висящую строго над этим блоком.
     *
     * P2-1: проверка строго на равенство blockX/blockZ (не ±1). Раньше
     * `<= 1` сносило голограммы соседних блоков — при bind/unbind одного
     * блока пропадала голограмма у соседнего и восстанавливалась только
     * при перезагрузке чанка или /caseadmin reload.
     *
     * Голограмма спавнится в x+0.5, z+0.5 — её getBlockX/getBlockZ равны
     * координатам самого блока, так что точное совпадение надёжно.
     */
    public void removeNear(@NotNull Location blockCenter) {
        Location search = blockCenter.clone().add(0, 1.0, 0);
        for (Entity e : search.getWorld().getNearbyEntities(search, 2.0, 5.0, 2.0)) {
            if (e.hasMetadata(METADATA_KEY)) {
                Location el = e.getLocation();
                if (el.getBlockX() == blockCenter.getBlockX()
                        && el.getBlockZ() == blockCenter.getBlockZ()) {
                    e.remove();
                }
            }
        }
    }

    private @NotNull Component buildText(@NotNull HologramConfig cfg) {
        List<Component> parts = new ArrayList<>();
        for (String line : cfg.lines()) {
            parts.add(MiniMessageUtil.parse(line));
        }
        Component result = parts.isEmpty() ? Component.empty() : parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = result.append(Component.newline()).append(parts.get(i));
        }
        return result;
    }
}