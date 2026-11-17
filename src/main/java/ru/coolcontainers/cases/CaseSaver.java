package ru.coolcontainers.cases;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.util.ItemStackSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Сохраняет CaseDefinition обратно в YAML.
 *
 * Для предметов:
 *  - если у предмета есть что-то сверх material+amount (displayName, lore,
 *    enchantments, CMD, NBT, атрибуты) — сохраняем его как base64;
 *  - иначе — простая модель (material + amount).
 */
public final class CaseSaver {

    private final JavaPlugin plugin;
    private final Logger log;
    private final Path casesDir;

    public CaseSaver(@NotNull JavaPlugin plugin, @NotNull Path casesDir) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.casesDir = casesDir;
    }

    public boolean save(@NotNull CaseDefinition def) {
        File file = new File(casesDir.toFile(), def.getId().toUpperCase() + ".yml");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("type", def.getType().name());
        yaml.set("display-name", def.getDisplayName());
        yaml.set("description", def.getDescription());
        yaml.set("permission", def.getPermission());

        yaml.set("hologram.enabled", def.getHologram().enabled());
        yaml.set("hologram.lines", def.getHologram().lines());
        yaml.set("hologram.offset-y", def.getHologram().offsetY());

        yaml.set("animation.type", def.getAnimation().type());
        for (var e : def.getAnimation().options().entrySet()) {
            yaml.set("animation." + e.getKey(), e.getValue());
        }

        List<Map<String, Object>> rewardsList = new ArrayList<>();
        for (Reward r : def.getRewards()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("display-name", r.getDisplayName());
            m.put("chance", r.getChance());
            m.put("amount", r.getAmount());

            if (!r.getItems().isEmpty()) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (ItemStack item : r.getItems()) {
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("material", item.getType().name());
                    im.put("amount", item.getAmount());

                    if (hasAnyMeta(item)) {
                        im.put("base64", ItemStackSerializer.toBase64(item));
                    }
                    items.add(im);
                }
                m.put("items", items);
            }
            if (!r.getCommands().isEmpty()) m.put("commands", r.getCommands());
            if (!r.getBroadcast().isEmpty()) m.put("broadcast", r.getBroadcast());
            if (r.getLuckPerms() != null) {
                Map<String, Object> lp = new LinkedHashMap<>();
                lp.put("group", r.getLuckPerms().group());
                lp.put("duration", r.getLuckPerms().duration());
                lp.put("server", r.getLuckPerms().server());
                m.put("luckperms", lp);
            }
            if (r.getVault() != null) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("amount", r.getVault().amount());
                m.put("vault", v);
            }
            if (r.getPlayerPoints() != null) {
                Map<String, Object> pp = new LinkedHashMap<>();
                pp.put("amount", r.getPlayerPoints().amount());
                m.put("playerpoints", pp);
            }
            rewardsList.add(m);
        }
        yaml.set("rewards", rewardsList);

        // === patch #6: атомарная запись через .tmp + ATOMIC_MOVE ===
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            yaml.save(tmp);
            java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            log.warning("Не удалось сохранить кейс " + def.getId() + ": " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    private static boolean hasAnyMeta(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) return false;
        var meta = item.getItemMeta();
        if (meta == null) return false;
        if (meta.hasDisplayName()) return true;
        if (meta.hasLore()) return true;
        if (meta.hasEnchants()) return true;
        if (meta.hasCustomModelData()) return true;
        if (!meta.getPersistentDataContainer().isEmpty()) return true;
        var attrs = meta.getAttributeModifiers();
        return attrs != null && !attrs.isEmpty();
    }
}