package ru.coolcontainers.command;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.CoolContainersPlugin;
import ru.coolcontainers.cases.dto.CaseDefinition;

import java.sql.SQLException;
import java.util.Locale;

public final class CaseCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CoolContainersPlugin plugin;

    public CaseCommand(@NotNull CoolContainersPlugin plugin) {
        this.plugin = plugin;
    }

    public void handle(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cКоманда доступна только игрокам.");
            return;
        }
        if (args.length == 0) {
            help(p);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            // === patch #9: open и menu — одна и та же логика ===
            case "open" -> doMenu(p, args);
            case "menu" -> doMenu(p, args);
            case "keys" -> doKeys(p, args);
            default -> help(p);
        }
    }

    private void help(Player p) {
        p.sendMessage("§6/case open <id>  §7— открыть кейс (радиус 5)");
        p.sendMessage("§6/case menu <id>  §7— открыть меню кейса (радиус 5)");
        p.sendMessage("§6/case keys [id]  §7— посмотреть ключи");
    }

    private void doMenu(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage("§c/case " + args[0] + " <id>"); return; }
        BlockHit hit = findNearbyBlock(p, args[1]);
        if (hit == null) return;
        CaseDefinition def = plugin.cases().get(args[1].toLowerCase(Locale.ROOT));
        if (def == null) { p.sendMessage("§cКейс не найден."); return; }
        if (!p.hasPermission(def.getPermission())) {
            p.sendMessage(MM.deserialize("<red>Недостаточно прав.</red>"));
            return;
        }
        plugin.caseMenuGui().open(p, def, hit.block());
    }

    private void doKeys(Player p, String[] args) {
        if (args.length >= 2) {
            String cid = args[1].toLowerCase(Locale.ROOT);
            try {
                int n = plugin.keyService().getAmount(p.getUniqueId(), cid);
                p.sendMessage("§eКлючей " + cid + ": §a" + n);
            } catch (SQLException e) {
                p.sendMessage("§cОшибка: " + e.getMessage());
            }
            return;
        }
        p.sendMessage("§6Твои ключи:");
        try {
            boolean any = false;
            for (String cid : plugin.cases().ids()) {
                int n = plugin.keyService().getAmount(p.getUniqueId(), cid);
                if (n > 0) { p.sendMessage("  §f" + cid + "§7: §a" + n); any = true; }
            }
            if (!any) p.sendMessage("  §7— пусто —");
        } catch (SQLException e) {
            p.sendMessage("§cОшибка: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Поиск блока кейса в радиусе 5
    // ------------------------------------------------------------------

    private record BlockHit(org.bukkit.block.Block block) {}

    private BlockHit findNearbyBlock(Player p, String caseIdRaw) {
        String caseId = caseIdRaw.toLowerCase(Locale.ROOT);
        Location center = p.getLocation();
        org.bukkit.World w = center.getWorld();
        if (w == null) return null;

        double best = Double.MAX_VALUE;
        org.bukkit.block.Block found = null;

        for (var entry : plugin.caseBlockService().snapshot().entrySet()) {
            var key = entry.getKey();
            if (!key.world().equals(w.getName())) continue;
            if (!entry.getValue().equals(caseId)) continue;

            Location blockLoc = new Location(w, key.x(), key.y(), key.z());
            double dist = blockLoc.distanceSquared(center);
            if (dist <= 25 && dist < best) { // 5^2
                best = dist;
                found = w.getBlockAt(key.x(), key.y(), key.z());
            }
        }

        if (found == null) {
            p.sendMessage("§cРядом нет блока кейса '" + caseId + "' (радиус 5).");
            return null;
        }
        return new BlockHit(found);
    }
}