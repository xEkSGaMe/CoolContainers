package ru.coolcontainers.animations;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.api.events.CaseOpenCancelEvent;
import ru.coolcontainers.api.events.CaseOpenCompleteEvent;
import ru.coolcontainers.block.BlockKey;
import ru.coolcontainers.block.BlockLockService;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.cases.dto.RewardSnapshot;
import ru.coolcontainers.config.Messages;
import ru.coolcontainers.db.DbExecutor;
import ru.coolcontainers.db.repository.OpeningRepository;
import ru.coolcontainers.key.KeyService;
import ru.coolcontainers.open.RewardGranter;
import ru.coolcontainers.open.RewardRoller;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Менеджер активных анимаций.
 *
 * Игрок может вести несколько анимаций одновременно — по одной на каждый
 * уникальный BlockKey. На одном блоке — не более одной сессии (гарантируется
 * BlockLockService.lockForAnimation).
 */
public final class AnimationService implements Listener {

    private static final double MAX_TELEPORT_DISTANCE_SQ = 100.0;

    private final JavaPlugin plugin;
    private final DbExecutor dbExecutor;
    private final Logger log;
    private final RewardRoller roller;
    private final RewardGranter granter;
    private final KeyService keyService;
    private final BlockLockService blockLocks;
    private final OpeningRepository openingRepo;
    private final int maxConcurrent;
    private final String serverId;

    private final Map<String, CaseAnimation> animations = new LinkedHashMap<>();
    private final Map<UUID, AnimationSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, UUID> entityToSession = new LinkedHashMap<>();

    /** playerUuid → (blockKey → sessionId). Игрок может держать несколько сессий на разных блоках. */
    private final Map<UUID, Map<BlockKey, UUID>> playerSessions = new LinkedHashMap<>();

    private long tickCounter = 0L;
    private int taskId = -1;

    public AnimationService(@NotNull JavaPlugin plugin,
                            @NotNull DbExecutor dbExecutor,
                            @NotNull RewardRoller roller,
                            @NotNull RewardGranter granter,
                            @NotNull KeyService keyService,
                            @NotNull BlockLockService blockLocks,
                            @NotNull OpeningRepository openingRepo,
                            int maxConcurrent,
                            @NotNull String serverId,
                            @NotNull CaseAnimation... animations) {
        this.plugin = plugin;
        this.dbExecutor = dbExecutor;
        this.log = plugin.getLogger();
        this.roller = roller;
        this.granter = granter;
        this.keyService = keyService;
        this.blockLocks = blockLocks;
        this.openingRepo = openingRepo;
        this.maxConcurrent = maxConcurrent;
        this.serverId = serverId;
        for (CaseAnimation a : animations) register(a);
    }

    public void register(@NotNull CaseAnimation animation) {
        animations.put(animation.type().toLowerCase(), animation);
    }

    public void start() {
        if (taskId != -1) return;
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }

        /*
         * P0: порядок "markRefunded → addKeys" обязателен.
         *
         * Если сделать наоборот и упасть между вызовами — opening остаётся
         * активным, но ключ уже у игрока. На следующем старте
         * OpenRecoveryService.refund вернёт ключ ещё раз (дюп) или выдаст
         * награду (дюп награды + ключ).
         *
         * Текущий порядок безопасен: если addKeys упадёт после markRefunded —
         * opening терминальный, ключ не вернулся. Худший исход — потеря ключа
         * у одного игрока при аварийном шатдауне. Это осознанный trade-off.
         */
        for (AnimationSession s : new ArrayList<>(sessions.values())) {
            try {
                s.cancel(KeyReason.REFUND_ABORTED);
                s.cleanup();
            } catch (Throwable t) {
                log.warning("stop: cleanup " + s.sessionId() + ": " + t.getMessage());
            }

            boolean marked = false;
            try {
                marked = openingRepo.markRefunded(s.openingId());
            } catch (SQLException e) {
                log.warning("stop: markRefunded " + s.sessionId()
                        + ": " + e.getMessage()
                        + " — opening оставлен активным, ключ НЕ возвращён.");
            }

            if (marked) {
                try {
                    keyService.addKeys(s.player().getUniqueId(),
                            s.definition().getId(),
                            1, KeyReason.REFUND_ABORTED, null);
                } catch (SQLException e) {
                    log.warning("stop: opening " + s.openingId()
                            + " помечен REFUNDED, но addKeys упал: " + e.getMessage()
                            + " — верни ключ вручную игроку " + s.player().getUniqueId()
                            + " case=" + s.definition().getId()
                            + " reason=" + KeyReason.REFUND_ABORTED);
                }
            } else {
                // Открытие уже терминальное (GRANTED/REFUNDED/FAILED) — ключ
                // уже был списан/возвращён/выдан другим путём. Ничего не делаем.
                log.info("stop: opening " + s.openingId()
                        + " уже в терминальном статусе — ключ не возвращён.");
            }

            try {
                blockLocks.forceUnlock(s.blockKey());
            } catch (Throwable ignored) {
            }
        }

        sessions.clear();
        entityToSession.clear();
        playerSessions.clear();
    }

    public boolean isAtMaxConcurrent() { return sessions.size() >= maxConcurrent; }
    public int activeCount() { return sessions.size(); }

    public void play(@NotNull UUID openingId,
                     @NotNull Player player,
                     @NotNull CaseDefinition def,
                     @NotNull Location blockLocation,
                     @NotNull BlockKey blockKey) {
        if (isAtMaxConcurrent()) {
            log.warning("Лимит анимаций (" + maxConcurrent + "), отказ для " + player.getName());
            blockLocks.forceUnlock(blockKey);
            refund(openingId, player.getUniqueId(), def.getId(), KeyReason.REFUND_ABORTED);
            return;
        }

        UUID playerUuid = player.getUniqueId();

        // Разрешаем несколько сессий, но на каждом блоке — одну.
        Map<BlockKey, UUID> playerMap = playerSessions.get(playerUuid);
        if (playerMap != null) {
            UUID existingId = playerMap.get(blockKey);
            if (existingId != null) {
                AnimationSession existing = sessions.get(existingId);
                if (existing != null && existing.state() == AnimationSession.State.RUNNING) {
                    log.warning("Игрок " + player.getName() + " уже открывает блок "
                            + blockKey + " (session=" + existingId + ") — отказ.");
                    blockLocks.forceUnlock(blockKey);
                    refund(openingId, playerUuid, def.getId(), KeyReason.REFUND_ABORTED);
                    return;
                }
            }
        }

        CaseAnimation animation = animations.get(def.getAnimation().type().toLowerCase());
        if (animation == null) {
            log.warning("Неизвестный тип анимации: " + def.getAnimation().type()
                    + " — выдаём сразу.");
            Reward reward = roller.roll(def);
            granter.grantAsync(openingId, player, def, reward,
                            new RewardSnapshot(reward.getId(), reward.getAmount()))
                    .whenComplete((ok, ex) -> dbExecutor.onMain(() -> {
                        if (ex != null) log.warning("grantAsync (no-anim): " + ex.getMessage());
                        blockLocks.unlockAndCooldown(blockKey);
                    }));
            return;
        }

        UUID sessionId = UUID.randomUUID();
        AnimationContext ctx = new AnimationContext(
                sessionId, openingId, player, def, roller, blockLocation, blockKey);

        AnimationSession session = animation.create(ctx, def.getAnimation().options());
        sessions.put(sessionId, session);
        playerSessions.computeIfAbsent(playerUuid, k -> new LinkedHashMap<>())
                .put(blockKey, sessionId);

        log.info("Анимация стартовала: player=" + player.getName()
                + " case=" + def.getId() + " block=" + blockKey
                + " session=" + sessionId + " server=" + serverId);
    }

    public boolean handleEntityClick(@NotNull Player clicker, @NotNull Entity entity) {
        UUID sessionId = entityToSession.get(entity.getUniqueId());
        if (sessionId == null) return false;
        AnimationSession session = sessions.get(sessionId);
        if (session == null) return false;
        return session.onEntityClick(entity, clicker);
    }

    public boolean isAnimationEntity(@NotNull Entity entity) {
        return entityToSession.containsKey(entity.getUniqueId());
    }

    private void tickAll() {
        tickCounter++;
        if (sessions.isEmpty()) return;

        List<AnimationSession> done = new ArrayList<>();
        for (AnimationSession s : new ArrayList<>(sessions.values())) {
            try {
                s.tick(tickCounter);
                registerEntities(s);
            } catch (Throwable t) {
                log.warning("tick " + s.sessionId() + ": " + t.getMessage());
                s.cancel(KeyReason.REFUND_ABORTED);
            }
            if (s.state() != AnimationSession.State.RUNNING) done.add(s);
        }
        for (AnimationSession s : done) finish(s);
    }

    private void finish(@NotNull AnimationSession s) {
        sessions.remove(s.sessionId());

        UUID playerUuid = s.player().getUniqueId();
        Map<BlockKey, UUID> playerMap = playerSessions.get(playerUuid);
        if (playerMap != null) {
            playerMap.remove(s.blockKey());
            if (playerMap.isEmpty()) playerSessions.remove(playerUuid);
        }

        unregisterEntities(s);

        try { s.cleanup(); }
        catch (Throwable t) { log.warning("finish.cleanup " + s.sessionId() + ": " + t.getMessage()); }

        try {
            if (s.state() == AnimationSession.State.COMPLETED) complete(s);
            else cancel(s);
        } catch (Throwable t) {
            log.warning("finish.process " + s.sessionId() + ": " + t.getMessage());
            try { blockLocks.forceUnlock(s.blockKey()); } catch (Throwable ignored) {}
            if (s.state() == AnimationSession.State.CANCELLED) {
                refund(s.openingId(), playerUuid, s.definition().getId(), KeyReason.REFUND_ABORTED);
            }
        }
    }

    private void complete(@NotNull AnimationSession s) {
        Reward reward = s.reward();
        RewardSnapshot snapshot = new RewardSnapshot(reward.getId(), reward.getAmount());

        granter.grantAsync(s.openingId(), s.player(), s.definition(), reward, snapshot)
                .whenComplete((ok, ex) -> dbExecutor.onMain(() -> {
                    blockLocks.unlockAndCooldown(s.blockKey());
                    if (ex != null) {
                        log.warning("grantAsync " + s.openingId() + ": " + ex.getMessage());
                        notifyGrantFailed(s);
                        return;
                    }
                    if (Boolean.TRUE.equals(ok)) notifyGrantSuccess(s, reward);
                    else notifyGrantFailed(s);
                }));
    }

    /** Личное сообщение + глобальный broadcast с обёрткой из messages.yml. */
    private void notifyGrantSuccess(@NotNull AnimationSession s, @NotNull Reward reward) {
        MiniMessage mm = MiniMessage.miniMessage();
        String playerName = s.player().getName();
        String caseDisplay = s.definition().getDisplayName();

        Messages msgs = null;
        if (plugin instanceof ru.coolcontainers.CoolContainersPlugin ccp) {
            msgs = ccp.messages();
        }

        // Личное сообщение (только победителю)
        if (s.player().isOnline()) {
            if (msgs != null) {
                s.player().sendMessage(msgs.component("case.won",
                        Map.of("reward", reward.getDisplayName())));
            } else {
                s.player().sendMessage(mm.deserialize(
                        "<white>Ты выиграл: " + reward.getDisplayName()));
            }
        }

        // Текст награды для broadcast: broadcast-строка из YAML (если есть), иначе display-name
        String rewardText;
        if (!reward.getBroadcast().isEmpty()) {
            rewardText = String.join("<newline>", reward.getBroadcast())
                    .replace("{player}", playerName);
        } else {
            rewardText = reward.getDisplayName();
        }

        // Глобальный broadcast через шаблон из messages.yml
        if (msgs != null) {
            Map<String, String> ph = Map.of(
                    "player", playerName,
                    "reward", rewardText,
                    "case",   caseDisplay);

            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.line-top", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.divider", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.brand", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.divider", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.spacer", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.win", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.spacer", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.divider", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.site", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.divider", ph));
            Bukkit.getServer().sendMessage(msgs.component("case.broadcast.line-bottom", ph));
        } else {
            // Fallback без messages.yml
            Bukkit.getServer().sendMessage(mm.deserialize(
                    "<gray>— " + playerName + " выиграл " + rewardText
                            + " из " + caseDisplay));
        }

        Bukkit.getPluginManager().callEvent(new CaseOpenCompleteEvent(
                s.openingId(), s.player(), s.definition().getId(),
                reward.getId(), reward.getAmount()));
    }

    private void notifyGrantFailed(@NotNull AnimationSession s) {
        MiniMessage mm = MiniMessage.miniMessage();
        if (s.player().isOnline()) {
            s.player().sendMessage(mm.deserialize(
                    "<red>Не удалось выдать награду. Обратись к администратору.</red>"));
        }
        Bukkit.getPluginManager().callEvent(new CaseOpenCancelEvent(
                s.openingId(), s.player(), s.definition().getId(),
                KeyReason.REFUND_ABORTED));
    }

    private void cancel(@NotNull AnimationSession s) {
        KeyReason reason = s.cancelReason() == null
                ? KeyReason.REFUND_ABORTED : s.cancelReason();
        try { refund(s.openingId(), s.player().getUniqueId(), s.definition().getId(), reason); }
        catch (Throwable t) { log.warning("cancel.refund " + s.sessionId() + ": " + t.getMessage()); }
        blockLocks.forceUnlock(s.blockKey());

        if (s.player().isOnline()) {
            try {
                Bukkit.getPluginManager().callEvent(new CaseOpenCancelEvent(
                        s.openingId(), s.player(), s.definition().getId(), reason));
            } catch (Throwable t) { log.warning("cancel.event: " + t.getMessage()); }
        }
    }

    /**
     * Асинхронный возврат ключа с защитой от дюпа.
     *
     * P0: markRefunded ВСЕГДА до addKeys. Тогда:
     *   - при сбое markRefunded — opening остаётся активным, ключ НЕ возвращён
     *     (recovery при следующем старте попробует ещё раз — но уже с одним
     *     активным opening, а не с двумя "возвращёнными" ключами);
     *   - при сбое addKeys после успешного markRefunded — opening терминальный,
     *     ключ потерян. Логируем явно, чтобы админ вернул вручную.
     *
     * Обратный порядок (addKeys → markRefunded), как было раньше, создавал
     * окно дюпа: ключ у игрока, opening активен → recovery возвращает ещё раз.
     */
    private void refund(@NotNull UUID openingId,
                        @NotNull UUID playerUuid,
                        @NotNull String caseId,
                        @NotNull KeyReason reason) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean marked;
            try {
                marked = openingRepo.markRefunded(openingId);
            } catch (SQLException e) {
                log.warning("refund: markRefunded " + openingId + ": " + e.getMessage()
                        + " — opening оставлен для recovery, ключ НЕ возвращён.");
                return;
            }

            if (!marked) {
                log.info("refund: opening " + openingId
                        + " уже в терминальном статусе — ключ не возвращён "
                        + "(защита от дюпа).");
                return;
            }

            try {
                keyService.addKeys(playerUuid, caseId, 1, reason, null);
            } catch (SQLException e) {
                log.warning("refund: opening " + openingId
                        + " помечен REFUNDED, но addKeys упал: " + e.getMessage()
                        + " — верни ключ вручную игроку " + playerUuid
                        + " case=" + caseId + " reason=" + reason);
            }
        });
    }

    private void registerEntities(@NotNull AnimationSession session) {
        for (Entity e : session.entities()) {
            entityToSession.putIfAbsent(e.getUniqueId(), session.sessionId());
        }
    }

    private void unregisterEntities(@NotNull AnimationSession session) {
        entityToSession.entrySet().removeIf(e -> e.getValue().equals(session.sessionId()));
    }

    // ------------------------------------------------------------------
    //  Listeners — отменяем все сессии игрока
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        Map<BlockKey, UUID> playerMap = playerSessions.get(playerUuid);
        if (playerMap == null || playerMap.isEmpty()) return;
        for (UUID sessionId : new ArrayList<>(playerMap.values())) {
            AnimationSession s = sessions.get(sessionId);
            if (s != null) s.cancel(KeyReason.REFUND_ABORTED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(@NotNull PlayerTeleportEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        Map<BlockKey, UUID> playerMap = playerSessions.get(playerUuid);
        if (playerMap == null || playerMap.isEmpty()) return;
        if (event.getTo() == null) return;

        for (UUID sessionId : new ArrayList<>(playerMap.values())) {
            AnimationSession s = sessions.get(sessionId);
            if (s == null) continue;

            if (!event.getTo().getWorld().getName().equals(s.blockKey().world())) {
                s.cancel(KeyReason.REFUND_ABORTED);
                continue;
            }
            double dx = event.getTo().getX() - s.blockKey().x();
            double dz = event.getTo().getZ() - s.blockKey().z();
            if (dx * dx + dz * dz > MAX_TELEPORT_DISTANCE_SQ) {
                s.cancel(KeyReason.REFUND_ABORTED);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        UUID playerUuid = event.getEntity().getUniqueId();
        Map<BlockKey, UUID> playerMap = playerSessions.get(playerUuid);
        if (playerMap == null || playerMap.isEmpty()) return;
        for (UUID sessionId : new ArrayList<>(playerMap.values())) {
            AnimationSession s = sessions.get(sessionId);
            if (s != null) s.cancel(KeyReason.REFUND_ABORTED);
        }
    }
}