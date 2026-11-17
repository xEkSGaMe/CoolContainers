package ru.coolcontainers.command;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.CoolContainersPlugin;
import ru.coolcontainers.api.enums.GrantStatus;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.api.enums.OpeningStatus;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.CaseType;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.cases.dto.RewardSnapshot;
import ru.coolcontainers.db.RetentionService;
import ru.coolcontainers.db.repository.GrantRepository;
import ru.coolcontainers.db.repository.OpeningRepository;
import ru.coolcontainers.gui.editor.CaseListGui;
import ru.coolcontainers.gui.editor.RewardListGui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class CaseAdminCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final CoolContainersPlugin plugin;
    private final Logger log;
    private final Map<UUID, String> pendingSetBlock = new ConcurrentHashMap<>();

    public CaseAdminCommand(@NotNull CoolContainersPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public @Nullable String consumePendingSetBlock(@NotNull Player player) {
        return pendingSetBlock.remove(player.getUniqueId());
    }

    public boolean hasPendingSetBlock(@NotNull Player player) {
        return pendingSetBlock.containsKey(player.getUniqueId());
    }

    public void handle(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 0) { help(sender); return; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "create" -> cmdCreate(sender, args);
                case "delete" -> cmdDelete(sender, args);
                case "edit" -> cmdEdit(sender, args);
                case "setblock" -> cmdSetBlock(sender, args);
                case "removeblock" -> cmdRemoveBlock(sender, args);
                case "setname" -> cmdSetName(sender, args);
                case "sethologram" -> cmdSetHologram(sender, args);
                case "animation" -> cmdSetAnimation(sender, args);
                case "givekey" -> cmdGiveKey(sender, args);
                case "takekey" -> cmdTakeKey(sender, args);
                case "setkey" -> cmdSetKey(sender, args);
                case "keys" -> cmdKeys(sender, args);
                case "logs" -> cmdLogs(sender, args);
                case "history" -> cmdHistory(sender, args);
                case "opening" -> cmdOpening(sender, args);
                case "recover" -> cmdRecover(sender, args);
                case "purge" -> cmdPurge(sender);
                case "reload" -> cmdReload(sender);
                case "stats" -> cmdStats(sender);
                default -> help(sender);
            }
        } catch (Exception e) {
            sender.sendMessage("§cОшибка: " + e.getMessage());
            log.warning("caseadmin /" + sub + " упал: " + e);
        }
    }

    private void help(@NotNull CommandSender s) {
        s.sendMessage("§6=== CoolContainers admin ===");
        s.sendMessage("§e/caseadmin create <id> <type>");
        s.sendMessage("§e/caseadmin delete <id>");
        s.sendMessage("§e/caseadmin edit [id]  §7— список кейсов / редактор наград");
        s.sendMessage("§e/caseadmin setblock <id>");
        s.sendMessage("§e/caseadmin removeblock");
        s.sendMessage("§e/caseadmin setname <id> <name>");
        s.sendMessage("§e/caseadmin sethologram <id> <line1|line2|...>");
        s.sendMessage("§e/caseadmin animation <id> <type>");
        s.sendMessage("§e/caseadmin givekey <player> <caseId> <amount>");
        s.sendMessage("§e/caseadmin takekey <player> <caseId> <amount>");
        s.sendMessage("§e/caseadmin setkey <player> <caseId> <amount>");
        s.sendMessage("§e/caseadmin keys <player> [caseId]");
        s.sendMessage("§e/caseadmin logs <player> [caseId]");
        s.sendMessage("§e/caseadmin history <player> [caseId]");
        s.sendMessage("§e/caseadmin opening <openingId>");
        s.sendMessage("§e/caseadmin recover <openingId> [grant|refund]");
        s.sendMessage("§e/caseadmin purge");
        s.sendMessage("§e/caseadmin reload");
        s.sendMessage("§e/caseadmin stats");
    }

    // ------------------------------------------------------------------
    //  create / delete
    // ------------------------------------------------------------------

    private void cmdCreate(CommandSender s, String[] args) throws IOException {
        if (args.length < 3) { s.sendMessage("§cИспользование: /caseadmin create <id> <type>"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]+")) {
            s.sendMessage("§cId кейса: только a-z, 0-9, _, -."); return;
        }
        if (plugin.cases().has(id)) {
            s.sendMessage("§cКейс с таким id уже существует."); return;
        }
        CaseType type = CaseType.fromString(args[2]);

        String yaml = """
                type: %TYPE%
                display-name: "<white>%ID%</white>"
                permission: cases.open.%ID%

                hologram:
                  enabled: true
                  lines:
                    - "<white>%ID%</white>"
                    - "<gray>ПКМ чтобы открыть</gray>"
                  offset-y: 1.4

                animation:
                  type: creeper

                rewards:
                  - id: example
                    display-name: "<gray>Пример награды</gray>"
                    chance: 100.0
                    amount: 1
                    items:
                      - material: STONE
                        amount: 1
                """.replace("%TYPE%", type.name()).replace("%ID%", id);

        Path target = plugin.casesDir().resolve(id.toUpperCase(Locale.ROOT) + ".yml");
        Files.writeString(target, yaml, StandardCharsets.UTF_8);

        plugin.reloadAll();
        // === patch #7: аудит ===
        audit(s, "create", Map.of("case", id, "type", type.name()));
        s.sendMessage("§aКейс " + id + " создан и загружен.");
    }

    private void cmdDelete(CommandSender s, String[] args) throws SQLException {
        if (args.length < 2) { s.sendMessage("§cИспользование: /caseadmin delete <id>"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        CaseDefinition def = plugin.cases().get(id);
        if (def == null) { s.sendMessage("§cКейс не найден."); return; }

        plugin.caseBlockService().unbindAll(id);

        File yaml = plugin.casesDir().resolve(def.getSourceFileName()).toFile();
        if (yaml.exists()) yaml.delete();

        plugin.cases().unregister(id);

        // === patch #11: чистим hash ===
        try {
            plugin.caseHashRepository().delete(id, plugin.config().getServerId());
        } catch (SQLException e) {
            log.warning("delete: не удалось удалить hash для " + id + ": " + e.getMessage());
        }

        // === patch #7: аудит ===
        audit(s, "delete", Map.of("case", id));
        s.sendMessage("§aКейс " + id + " удалён.");
    }

    // ------------------------------------------------------------------
    //  edit / setblock / removeblock
    // ------------------------------------------------------------------

    private void cmdEdit(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("§cТолько для игроков."); return; }

        if (args.length < 2) {
            new CaseListGui(plugin, plugin.guiManager()).open(p);
            return;
        }

        CaseDefinition def = plugin.cases().get(args[1].toLowerCase(Locale.ROOT));
        if (def == null) { s.sendMessage("§cКейс не найден."); return; }
        new RewardListGui(plugin, plugin.guiManager()).open(p, def.getId());
    }

    private void cmdSetBlock(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("§cТолько для игроков."); return; }
        if (args.length < 2) { s.sendMessage("§cИспользование: /caseadmin setblock <id>"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!plugin.cases().has(id)) { s.sendMessage("§cКейс не найден."); return; }
        pendingSetBlock.put(p.getUniqueId(), id);
        p.sendMessage("§aПКМ по блоку, чтобы привязать кейс " + id);
    }

    private void cmdRemoveBlock(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("§cТолько для игроков."); return; }
        var target = p.getTargetBlockExact(5);
        if (target == null) { p.sendMessage("§cСмотри на блок ближе."); return; }
        String caseId = plugin.caseBlockService().getCaseAt(target.getLocation());
        if (caseId == null) { p.sendMessage("§cЭто не блок кейса."); return; }
        try {
            plugin.caseBlockService().unbind(target.getLocation());
            p.sendMessage("§aБлок отвязан от " + caseId);
        } catch (SQLException e) {
            p.sendMessage("§cОшибка: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  setname / sethologram / animation
    // ------------------------------------------------------------------

    private void cmdSetName(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage("§cИспользование: /caseadmin setname <id> <name>"); return; }
        CaseDefinition def = plugin.cases().get(args[1].toLowerCase(Locale.ROOT));
        if (def == null) { s.sendMessage("§cКейс не найден."); return; }

        String name = join(args, 2);
        CaseDefinition newDef = def.withDisplayName(name);
        plugin.cases().register(newDef);
        plugin.saveCase(newDef);
        plugin.caseBlockService().refreshAllHolograms();
        // === patch #7 ===
        audit(s, "setname", Map.of("case", def.getId(), "name", name));
        s.sendMessage("§aНазвание обновлено.");
    }

    private void cmdSetHologram(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage("§cИспользование: /caseadmin sethologram <id> <line1|line2|...>"); return; }
        CaseDefinition def = plugin.cases().get(args[1].toLowerCase(Locale.ROOT));
        if (def == null) { s.sendMessage("§cКейс не найден."); return; }

        String raw = join(args, 2);
        List<String> lines = List.of(raw.split("\\|"));
        CaseDefinition newDef = def.withHologramLines(lines);
        plugin.cases().register(newDef);
        plugin.saveCase(newDef);
        plugin.caseBlockService().refreshAllHolograms();
        // === patch #7 ===
        audit(s, "sethologram", Map.of("case", def.getId(), "lines", lines.size()));
        s.sendMessage("§aГолограмма обновлена (" + lines.size() + " строк).");
    }

    private void cmdSetAnimation(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage("§cИспользование: /caseadmin animation <id> <type>"); return; }
        CaseDefinition def = plugin.cases().get(args[1].toLowerCase(Locale.ROOT));
        if (def == null) { s.sendMessage("§cКейс не найден."); return; }

        CaseDefinition newDef = def.withAnimationType(args[2].toLowerCase(Locale.ROOT));
        plugin.cases().register(newDef);
        plugin.saveCase(newDef);
        // === patch #7 ===
        audit(s, "animation", Map.of("case", def.getId(), "type", args[2]));
        s.sendMessage("§aАнимация = " + args[2]);
    }

    // ------------------------------------------------------------------
    //  keys
    // ------------------------------------------------------------------

    private void cmdGiveKey(CommandSender s, String[] args) throws SQLException {
        if (args.length < 4) { s.sendMessage("§c/caseadmin givekey <player> <caseId> <amount>"); return; }
        OfflinePlayer target = resolvePlayer(s, args[1]); if (target == null) return;
        String cid = args[2].toLowerCase(Locale.ROOT);
        if (!plugin.cases().has(cid)) { s.sendMessage("§cКейс не найден."); return; }
        int amount = parseInt(s, args[3]); if (amount < 0) return;

        int now = plugin.keyService().addKeys(target.getUniqueId(), cid,
                amount, KeyReason.ADMIN_GIVE, adminUuid(s));
        // === patch #7 ===
        audit(s, "givekey", Map.of(
                "target", target.getUniqueId().toString(),
                "case",   cid,
                "amount", amount,
                "now",    now));
        s.sendMessage("§aВыдано " + amount + " ключей кейса " + args[2] + " игроку "
                + target.getName() + ". Всего: " + now);
    }

    private void cmdTakeKey(CommandSender s, String[] args) throws SQLException {
        if (args.length < 4) { s.sendMessage("§c/caseadmin takekey <player> <caseId> <amount>"); return; }
        OfflinePlayer target = resolvePlayer(s, args[1]); if (target == null) return;
        String cid = args[2].toLowerCase(Locale.ROOT);
        if (!plugin.cases().has(cid)) { s.sendMessage("§cКейс не найден."); return; }
        int amount = parseInt(s, args[3]); if (amount < 0) return;

        boolean ok = plugin.keyService().takeKeys(target.getUniqueId(), cid,
                amount, KeyReason.ADMIN_TAKE, adminUuid(s));
        if (!ok) { s.sendMessage("§cУ игрока недостаточно ключей."); return; }
        int now = plugin.keyService().getAmount(target.getUniqueId(), cid);
        // === patch #7 ===
        audit(s, "takekey", Map.of(
                "target", target.getUniqueId().toString(),
                "case",   cid,
                "amount", amount));
        s.sendMessage("§aИзъято " + amount + " ключей кейса " + args[2] + ". Осталось: " + now);
    }

    private void cmdSetKey(CommandSender s, String[] args) throws SQLException {
        if (args.length < 4) { s.sendMessage("§c/caseadmin setkey <player> <caseId> <amount>"); return; }
        OfflinePlayer target = resolvePlayer(s, args[1]); if (target == null) return;
        String cid = args[2].toLowerCase(Locale.ROOT);
        if (!plugin.cases().has(cid)) { s.sendMessage("§cКейс не найден."); return; }
        int amount = parseInt(s, args[3]); if (amount < 0) return;

        plugin.keyService().setKeys(target.getUniqueId(), cid, amount, adminUuid(s));
        // === patch #7 ===
        audit(s, "setkey", Map.of(
                "target", target.getUniqueId().toString(),
                "case",   cid,
                "amount", amount));
        s.sendMessage("§aУстановлено " + amount + " ключей кейса " + args[2] + ".");
    }

    private void cmdKeys(CommandSender s, String[] args) throws SQLException {
        if (args.length < 2) { s.sendMessage("§c/caseadmin keys <player> [caseId]"); return; }
        OfflinePlayer target = resolvePlayer(s, args[1]); if (target == null) return;

        if (args.length >= 3) {
            String cid = args[2].toLowerCase(Locale.ROOT);
            int n = plugin.keyService().getAmount(target.getUniqueId(), cid);
            s.sendMessage("§e" + target.getName() + " §7→ §f" + cid + "§7: §a" + n);
            return;
        }
        s.sendMessage("§6Ключи " + target.getName() + ":");
        for (String cid : plugin.cases().ids()) {
            int n = plugin.keyService().getAmount(target.getUniqueId(), cid);
            if (n > 0) s.sendMessage("  §f" + cid + "§7: §a" + n);
        }
    }

    // ------------------------------------------------------------------
    //  logs / history / opening
    // ------------------------------------------------------------------

    private void cmdLogs(CommandSender s, String[] args) throws SQLException {
        if (args.length < 2) { s.sendMessage("§c/caseadmin logs <player> [caseId]"); return; }
        OfflinePlayer target = resolvePlayer(s, args[1]); if (target == null) return;
        String cid = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : null;

        List<OpeningRepository.OpeningRow> list =
                plugin.openingRepository().findRecent(target.getUniqueId(), cid, 10);
        s.sendMessage("§6Последние открытия " + target.getName() + ":");
        for (OpeningRepository.OpeningRow row : list) {
            s.sendMessage("  §7" + DATE.format(row.createdAt())
                    + " §f" + row.caseId()
                    + " §7→ §e" + row.status()
                    + " §7(" + row.openingId() + ")");
        }
        if (list.isEmpty()) s.sendMessage("  §7— пусто —");
    }

    private void cmdHistory(CommandSender s, String[] args) throws SQLException {
        if (args.length < 2) { s.sendMessage("§c/caseadmin history <player> [caseId]"); return; }
        OfflinePlayer target = resolvePlayer(s, args[1]); if (target == null) return;
        String cid = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : null;

        var list = plugin.keyRepository().findTransactions(target.getUniqueId(), cid, 15);
        s.sendMessage("§6История ключей " + target.getName() + ":");
        for (var tx : list) {
            String sign = tx.delta() > 0 ? "§a+" : "§c";
            s.sendMessage("  §7" + DATE.format(tx.createdAt())
                    + " §f" + tx.caseId()
                    + " " + sign + tx.delta()
                    + " §7(" + tx.reason() + ")");
        }
        if (list.isEmpty()) s.sendMessage("  §7— пусто —");
    }

    private void cmdOpening(CommandSender s, String[] args) throws SQLException {
        if (args.length < 2) { s.sendMessage("§c/caseadmin opening <openingId>"); return; }
        UUID id;
        try { id = UUID.fromString(args[1]); }
        catch (IllegalArgumentException e) { s.sendMessage("§cНе UUID."); return; }

        OpeningRepository.OpeningRow row = plugin.openingRepository().findById(id);
        if (row == null) { s.sendMessage("§cOpening не найден."); return; }

        s.sendMessage("§6Opening " + id);
        s.sendMessage("  игрок:  §f" + row.playerUuid());
        s.sendMessage("  кейс:   §f" + row.caseId());
        s.sendMessage("  статус: §e" + row.status());
        s.sendMessage("  ключей: §f" + row.keysSpent());
        s.sendMessage("  создан: §f" + DATE.format(row.createdAt()));
        if (row.completedAt() != null) s.sendMessage("  готов:  §f" + DATE.format(row.completedAt()));
        if (row.rewardSnapshot() != null) {
            s.sendMessage("  награда: §f" + row.rewardSnapshot().rewardId()
                    + " x" + row.rewardSnapshot().amount());
        }

        var grants = plugin.grantRepository().findByOpening(id);
        if (!grants.isEmpty()) {
            s.sendMessage("  §6grants:");
            for (var g : grants) {
                s.sendMessage("    §f" + g.rewardId() + " §7— §e" + g.status()
                        + (g.error() != null ? " §c(" + g.error() + ")" : ""));
            }
        }
    }

    // ------------------------------------------------------------------
    //  recover / purge / reload / stats
    // ------------------------------------------------------------------

    private void cmdRecover(CommandSender s, String[] args) throws SQLException {
        if (args.length < 2) { s.sendMessage("§c/caseadmin recover <openingId> [grant|refund]"); return; }
        UUID id;
        try { id = UUID.fromString(args[1]); }
        catch (IllegalArgumentException e) { s.sendMessage("§cНе UUID."); return; }

        OpeningRepository.OpeningRow row = plugin.openingRepository().findById(id);
        if (row == null) { s.sendMessage("§cOpening не найден."); return; }

        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";

        if (action.isEmpty()) {
            s.sendMessage("§eOpening " + id + " статус: " + row.status());
            s.sendMessage("§7Опции: /caseadmin recover " + id + " grant|refund");
            return;
        }

        // === refund ===
        if (action.equals("refund")) {
            if (row.status() == OpeningStatus.REFUNDED) { s.sendMessage("§eУже REFUNDED."); return; }
            if (row.status() == OpeningStatus.GRANTED)  { s.sendMessage("§cУже GRANTED, отказ."); return; }

            // === P0: если в opening'е есть GRANTED-грант, side-effect уже
            // прошёл (награда выдана), даже если opening застрял в GRANTING/
            // FAILED из-за сбоя markGranted. Возврат ключа в этом случае даст
            // дюп «ключ + награда».
            List<GrantRepository.GrantRow> grants = plugin.grantRepository().findByOpening(id);
            boolean sideEffectAlreadyRan = grants.stream()
                    .anyMatch(g -> g.status() == GrantStatus.GRANTED);
            if (sideEffectAlreadyRan) {
                s.sendMessage("§cOpening " + id + " содержит GRANTED-грант: "
                        + "награда была выдана игроку. Возврат ключа даст дюп — отказ. "
                        + "Проверь инвентарь / историю LP / баланс Vault/PP вручную "
                        + "и при необходимости верни ключ через /caseadmin givekey.");
                return;
            }

            // === P1: FAILED → REFUNDED через forceRefunded. Это единственный
            // «безопасный» путь восстановления после частичного side-effect:
            // админ получает warning, сам проверяет, что прошло, и решает.
            boolean marked;
            if (row.status() == OpeningStatus.FAILED) {
                marked = plugin.openingRepository().forceRefunded(id);
                if (marked) {
                    s.sendMessage("§eВнимание: opening был в FAILED — side-effect мог "
                            + "пройти частично (LP/Vault/PP/items/commands). Ключ будет "
                            + "возвращён, но награда могла уже частично выдаться. "
                            + "Проверь инвентарь / историю LP / баланс Vault/PP вручную.");
                }
            } else {
                marked = plugin.openingRepository().markRefunded(id);
            }

            if (!marked) {
                s.sendMessage("§cOpening " + id + " не переведён в REFUNDED "
                        + "(уже терминальный или обрабатывается).");
                return;
            }

            int failedGrants = plugin.grantRepository().markAllProcessingFailed(
                    id, "admin recover refund");

            int keys = Math.max(1, row.keysSpent());
            try {
                plugin.keyService().addKeys(row.playerUuid(), row.caseId(), keys,
                        KeyReason.REFUND_RECOVERY, adminUuid(s));
            } catch (SQLException e) {
                s.sendMessage("§cСтатус уже REFUNDED, но вернуть ключи не удалось: "
                        + e.getMessage() + " — верни вручную.");
                return;
            }

            audit(s, "recover.refund", Map.of(
                    "opening", id.toString(),
                    "keys",    keys,
                    "grants",  failedGrants,
                    "from",    row.status().name()));
            s.sendMessage("§aOpening " + id + " → REFUNDED, ключей возвращено: " + keys
                    + (failedGrants > 0 ? ", PROCESSING grants закрыто: " + failedGrants : ""));
            return;
        }

        // ---- GRANT ----
        if (action.equals("grant")) {
            if (row.status() == OpeningStatus.GRANTED) { s.sendMessage("§eУже GRANTED."); return; }
            if (row.status() == OpeningStatus.REFUNDED) { s.sendMessage("§cУже REFUNDED, отказ."); return; }

            // === P1: FAILED → grant запрещён. Даже если side-effect «не прошёл»,
            // нет способа отличить полный провал от частичного. Пусть админ
            // выдаст вручную через givekey, если уверен, что награда не выдана.
            if (row.status() == OpeningStatus.FAILED) {
                s.sendMessage("§cOpening в FAILED — recover grant запрещён "
                        + "(риск повторной частичной выдачи). "
                        + "Если награда точно не выдана — используй givekey + ручную выдачу.");
                return;
            }

            // === P0: GRANTING через recover grant запрещён (side-effect мог
            // уже сработать). Раньше это «молча» возвращало false из
            // updateStatusFromAny — теперь явный отказ с пояснением.
            if (row.status() == OpeningStatus.GRANTING) {
                s.sendMessage("§cOpening в GRANTING — recover grant запрещён "
                        + "(side-effect мог уже сработать). "
                        + "Используй recover refund, если награда точно не выдана.");
                return;
            }

            Player online = Bukkit.getPlayer(row.playerUuid());
            if (online == null) { s.sendMessage("§cИгрок оффлайн — используй refund."); return; }

            CaseDefinition def = plugin.cases().get(row.caseId());
            if (def == null) { s.sendMessage("§cКейс не найден в реестре."); return; }

            Reward reward = plugin.rewardRoller().roll(def);
            RewardSnapshot snap = new RewardSnapshot(reward.getId(), reward.getAmount());

            plugin.grantRepository().markAllProcessingFailed(id, "admin recover: заменён ручным");

            plugin.rewardGranter().grantAdminAsync(id, online, def, reward, snap)
                    .whenComplete((ok, ex) -> plugin.dbExecutor().onMain(() -> {
                        if (ex != null) {
                            s.sendMessage("§cОшибка: " + ex.getMessage());
                            return;
                        }
                        audit(s, "recover.grant", Map.of(
                                "opening", id.toString(),
                                "reward",  reward.getId(),
                                "success", Boolean.TRUE.equals(ok)));
                        s.sendMessage(Boolean.TRUE.equals(ok)
                                ? "§aНаграда выдана: " + reward.getId()
                                : "§cНе удалось (см. лог). Opening оставлен в терминальном статусе.");
                    }));
            return;
        }

        s.sendMessage("§cНеизвестное действие: " + action);
    }

    private void cmdPurge(CommandSender s) {
        s.sendMessage("§6Запускаю очистку...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<RetentionService.PurgeReport> reports = plugin.retentionService().runOnce();
                StringBuilder sb = new StringBuilder("§aОчистка завершена:\n");
                int total = 0;
                for (RetentionService.PurgeReport r : reports) {
                    sb.append("§7  ").append(r.table()).append("§7: §f").append(r.deleted()).append('\n');
                    total += r.deleted();
                }
                sb.append("§7  всего: §f").append(total);
                String msg = sb.toString();
                Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(msg));
            } catch (SQLException e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        s.sendMessage("§cОшибка очистки: " + e.getMessage()));
            }
        });
    }

    private void cmdReload(CommandSender s) {
        plugin.reloadAll();
        s.sendMessage("§aКонфиги и кейсы перезагружены. Загружено кейсов: " + plugin.cases().size());
    }

    private void cmdStats(CommandSender s) {
        s.sendMessage("§6CoolContainers stats:");
        s.sendMessage("  server-id: §f" + plugin.config().getServerId());
        s.sendMessage("  кейсов:    §f" + plugin.cases().size());
        s.sendMessage("  блоков:    §f" + plugin.caseBlockService().snapshot().size());
        s.sendMessage("  анимаций:  §f" + plugin.animationService().activeCount()
                + " §7/ §f" + plugin.config().getMaxConcurrentAnimations());
        s.sendMessage("  БД:        §f" + plugin.db().isHealthy());
    }

    // ------------------------------------------------------------------
    //  Хелперы
    // ------------------------------------------------------------------

    private @Nullable OfflinePlayer resolvePlayer(CommandSender s, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (op.hasPlayedBefore() || op.isOnline()) return op;
        s.sendMessage("§cИгрок '" + name + "' не найден.");
        return null;
    }

    private int parseInt(CommandSender s, String raw) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException e) { s.sendMessage("§cНе число: " + raw); return -1; }
    }

    private @Nullable UUID adminUuid(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId() : null;
    }

    private static @NotNull String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // === patch #7 ===
    private void audit(@NotNull CommandSender s,
                       @NotNull String action,
                       @Nullable Object payload) {
        try {
            UUID admin = adminUuid(s);
            if (admin == null) {
                plugin.adminLogRepository().logSystem(action, payload);
            } else {
                plugin.adminLogRepository().log(admin, action, payload);
            }
        } catch (SQLException e) {
            log.warning("audit: не удалось записать лог '" + action + "': " + e.getMessage());
        }
    }
}