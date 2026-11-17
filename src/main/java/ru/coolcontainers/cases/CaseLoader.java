package ru.coolcontainers.cases;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.cases.dto.*;
import ru.coolcontainers.util.HashUtil;
import ru.coolcontainers.util.ItemStackSerializer;
import ru.coolcontainers.util.MiniMessageUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Парсит cases/*.yml → CaseDefinition.
 * Ошибка в одном файле не валит остальные.
 */
public final class CaseLoader {

    public record LoadResult(Map<String, CaseDefinition> loaded,
                             Map<String, List<String>> errors) {}

    private final JavaPlugin plugin;
    private final Logger log;

    public CaseLoader(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public @NotNull LoadResult loadAll(@NotNull Path casesDir) {
        Map<String, CaseDefinition> loaded = new LinkedHashMap<>();
        Map<String, List<String>> errors = new LinkedHashMap<>();

        File dir = casesDir.toFile();
        if (!dir.isDirectory()) {
            log.warning("Папка cases/ не найдена: " + casesDir);
            return new LoadResult(loaded, errors);
        }

        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null || files.length == 0) {
            log.info("cases/ пуст — нечего загружать.");
            return new LoadResult(loaded, errors);
        }

        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            String caseId = stripExtension(file.getName()).toLowerCase(Locale.ROOT);
            try {
                CaseDefinition def = loadOne(file, caseId);
                List<String> validationErrors = CaseValidator.validate(def);
                if (!validationErrors.isEmpty()) {
                    errors.put(caseId, validationErrors);
                    log.warning("Кейс '" + caseId + "' не загружен, ошибки: " + validationErrors);
                    continue;
                }
                loaded.put(caseId, def);
                log.info("Кейс загружен: " + caseId
                        + " (тип=" + def.getType()
                        + ", награда=" + def.getRewards().size()
                        + ", hash=" + shortHash(def.getContentHash()) + ")");
            } catch (Exception e) {
                List<String> errs = List.of("исключение: " + e.getMessage());
                errors.put(caseId, errs);
                log.log(Level.WARNING, "Ошибка загрузки кейса '" + caseId + "'", e);
            }
        }

        return new LoadResult(loaded, errors);
    }

    // ------------------------------------------------------------------
    //  Внутреннее
    // ------------------------------------------------------------------

    private @NotNull CaseDefinition loadOne(@NotNull File file, @NotNull String caseId) throws Exception {
        String rawText = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        String normalized = HashUtil.normalize(rawText);
        String contentHash = HashUtil.sha256(normalized);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(rawText);

        CaseType type = CaseType.fromString(yaml.getString("type", "CUSTOM"));
        String displayName = yaml.getString("display-name", "<white>" + caseId + "</white>");
        String description = yaml.getString("description", "");
        String permission = yaml.getString("permission", "cases.open." + caseId);

        HologramConfig hologram = parseHologram(yaml.getConfigurationSection("hologram"));
        AnimationConfig animation = parseAnimation(yaml.getConfigurationSection("animation"));
        List<Reward> rewards = parseRewards(yaml.getMapList("rewards"));

        CaseDefinition def = new CaseDefinition(
                caseId, type, displayName, description, permission,
                hologram, animation, rewards, file.getName()
        );
        def.setContentHash(contentHash);
        return def;
    }

    private @NotNull HologramConfig parseHologram(@Nullable ConfigurationSection sec) {
        if (sec == null || !sec.getBoolean("enabled", true)) return HologramConfig.disabled();
        List<String> lines = sec.getStringList("lines");
        double offsetY = sec.getDouble("offset-y", 1.2);
        return new HologramConfig(true, lines, offsetY);
    }

    private @NotNull AnimationConfig parseAnimation(@Nullable ConfigurationSection sec) {
        if (sec == null) return AnimationConfig.creeper();
        String type = sec.getString("type", AnimationConfig.TYPE_CREEPER);
        Map<String, Object> options = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            if (!key.equals("type")) options.put(key, sec.get(key));
        }
        return new AnimationConfig(type, options);
    }

    private @NotNull List<Reward> parseRewards(@NotNull List<Map<?, ?>> raw) {
        List<Reward> out = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            try {
                out.add(parseReward(m));
            } catch (Exception e) {
                log.warning("Пропущена награда из-за ошибки: " + e.getMessage());
            }
        }
        return out;
    }

    private @NotNull Reward parseReward(@NotNull Map<?, ?> m) {
        Object idRaw = m.get("id");
        if (idRaw == null) {
            throw new IllegalArgumentException("reward.id отсутствует");
        }
        String id = String.valueOf(idRaw).trim();
        if (id.isEmpty() || "null".equals(id)) {
            throw new IllegalArgumentException("reward.id пуст");
        }

        Reward.Builder b = Reward.builder(id);

        Object displayRaw = m.get("display-name");
        if (displayRaw != null) b.displayName(String.valueOf(displayRaw));

        Object chanceRaw = m.get("chance");
        if (chanceRaw != null) b.chance(toDouble(chanceRaw));

        Object amountRaw = m.get("amount");
        if (amountRaw != null) b.amount(toInt(amountRaw));

        Object itemsRaw = m.get("items");
        if (itemsRaw instanceof List<?> list) {
            List<ItemStack> items = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> im) {
                    ItemStack st = parseItem(im);
                    if (st != null) items.add(st);
                }
            }
            b.items(items);
        }

        Object cmdsRaw = m.get("commands");
        if (cmdsRaw instanceof List<?> list) {
            List<String> cmds = new ArrayList<>();
            for (Object o : list) cmds.add(String.valueOf(o));
            b.commands(cmds);
        }

        Object bcRaw = m.get("broadcast");
        if (bcRaw instanceof List<?> list) {
            List<String> bc = new ArrayList<>();
            for (Object o : list) bc.add(String.valueOf(o));
            b.broadcast(bc);
        }

        if (m.get("luckperms") instanceof Map<?, ?> lp) {
            Object group = lp.get("group");
            Object duration = lp.get("duration");
            Object server = lp.get("server");
            b.luckPerms(new Reward.LuckPermsEntry(
                    group == null ? "" : String.valueOf(group),
                    duration == null ? "permanent" : String.valueOf(duration),
                    server == null ? "global" : String.valueOf(server)
            ));
        }

        if (m.get("vault") instanceof Map<?, ?> v) {
            Object amount = v.get("amount");
            b.vault(new Reward.VaultEntry(amount == null ? 0 : toDouble(amount)));
        }

        if (m.get("playerpoints") instanceof Map<?, ?> pp) {
            Object amount = pp.get("amount");
            b.playerPoints(new Reward.PlayerPointsEntry(amount == null ? 0 : toInt(amount)));
        }

        return b.build();
    }

    private @Nullable ItemStack parseItem(@NotNull Map<?, ?> m) {
        Object base64 = m.get("base64");
        if (base64 instanceof String b64 && !b64.isBlank()) {
            ItemStack st = ItemStackSerializer.fromBase64(b64);
            if (st != null) return st;
            log.warning("Не удалось разобрать base64-предмет, пробуем через material");
        }

        Object matRaw = m.get("material");
        if (!(matRaw instanceof String matName)) return null;

        Material material = Material.matchMaterial(matName);
        if (material == null || material.isAir()) {
            log.warning("Неизвестный material: " + matName);
            return null;
        }

        Object amountRaw = m.get("amount");
        int amount = amountRaw == null ? 1 : toInt(amountRaw);
        ItemStack st = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = st.getItemMeta();

        Object nameRaw = m.get("name");
        if (nameRaw instanceof String name && !name.isBlank()) {
            meta.displayName(MiniMessageUtil.parse(name));
        }

        Object loreRaw = m.get("lore");
        if (loreRaw instanceof List<?> lore) {
            List<net.kyori.adventure.text.Component> loreComponents = new ArrayList<>();
            for (Object o : lore) loreComponents.add(MiniMessageUtil.parse(String.valueOf(o)));
            meta.lore(loreComponents);
        }

        Object cmdRaw = m.get("custom-model-data");
        if (cmdRaw != null) {
            meta.setCustomModelData(toInt(cmdRaw));
        }

        Object enchRaw = m.get("enchantments");
        if (enchRaw instanceof List<?> list) {
            for (Object e : list) {
                String[] parts = String.valueOf(e).split(":", 2);
                if (parts.length != 2) continue;
                NamespacedKey key = NamespacedKey.minecraft(parts[0].toLowerCase(Locale.ROOT));
                Enchantment ench = org.bukkit.Registry.ENCHANTMENT.get(key);
                if (ench == null) {
                    log.warning("Неизвестный enchantment: " + parts[0]);
                    continue;
                }
                meta.addEnchant(ench, toInt(parts[1]), true);
            }
        }

        st.setItemMeta(meta);
        return st;
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static double toDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String shortHash(@Nullable String hash) {
        return hash == null ? "-" : hash.substring(0, 8);
    }
}