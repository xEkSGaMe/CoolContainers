package ru.coolcontainers.animations.creeper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.animations.AnimationContext;
import ru.coolcontainers.animations.AnimationSession;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.block.BlockKey;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.open.RewardRoller;
import ru.coolcontainers.util.MiniMessageUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Анимация: N криперов вокруг блока. Фазовое построение.
 */
public final class CreeperSession implements AnimationSession {

    private static final int CHARGE_HOLD_TICKS = 20;
    private static final int FACE_UPDATE_INTERVAL = 2;
    private static final int CLICK_WARN_AFTER_SECONDS = 5;

    private static final String META_CREEPER = "cc_creeper";
    private static final String META_DROP = "cc_anim_drop";
    private static final String META_TEXT = "cc_anim_text";

    private final UUID sessionId;
    private final UUID openingId;
    private final Player player;
    private final CaseDefinition definition;
    private final Location blockLocation;
    private final BlockKey blockKey;
    private final CreeperSettings settings;
    private final JavaPlugin plugin;
    private final int totalCreepers;

    private final List<Reward> rolledRewards = new ArrayList<>();
    private final List<Creeper> creepers = new ArrayList<>();
    private final List<Item> activeDrops = new ArrayList<>();
    private final List<TextDisplay> activeTexts = new ArrayList<>();

    private int spawnIndex = 0;
    private long spawnCompleteTick = -1;
    private long lastFaceUpdateTick = 0;

    private int chosenIndex = -1;
    private Creeper chosen;
    private long clickedTick = -1;
    private boolean othersRemoved = false;
    private boolean exploded = false;
    private long displayUntilTick = -1;

    private boolean warnedClick = false;
    private @Nullable Location limeDropLoc;
    private long lastFlameTick = 0;

    private long createdAtTick = 0L;
    private long lastTick = -1L;

    private State state = State.RUNNING;
    private KeyReason cancelReason;

    public CreeperSession(@NotNull AnimationContext ctx,
                          @NotNull CreeperSettings settings,
                          @NotNull JavaPlugin plugin) {
        this.sessionId = ctx.sessionId();
        this.openingId = ctx.openingId();
        this.player = ctx.player();
        this.definition = ctx.definition();
        this.blockLocation = ctx.blockLocation();
        this.blockKey = ctx.blockKey();
        this.settings = settings;
        this.plugin = plugin;
        this.totalCreepers = Math.max(1, settings.creeperCount());

        RewardRoller roller = ctx.roller();
        for (int i = 0; i < totalCreepers; i++) {
            rolledRewards.add(roller.roll(definition));
        }
    }

    // ------------------------------------------------------------------
    //  AnimationSession
    // ------------------------------------------------------------------

    @Override public @NotNull UUID sessionId() { return sessionId; }
    @Override public @NotNull UUID openingId() { return openingId; }
    @Override public @NotNull Player player() { return player; }
    @Override public @NotNull CaseDefinition definition() { return definition; }

    @Override
    public @NotNull Reward reward() {
        int idx = chosenIndex >= 0 ? chosenIndex : 0;
        return rolledRewards.get(idx);
    }

    @Override public @NotNull BlockKey blockKey() { return blockKey; }
    @Override public @NotNull State state() { return state; }
    @Override public @Nullable KeyReason cancelReason() { return cancelReason; }

    @Override
    public @NotNull Collection<Entity> entities() {
        List<Entity> out = new ArrayList<>(
                creepers.size() + activeDrops.size() + activeTexts.size());
        for (Creeper c : creepers) {
            if (c != null && !c.isDead() && c.isValid()) out.add(c);
        }
        for (Item i : activeDrops) {
            if (i != null && !i.isDead() && i.isValid()) out.add(i);
        }
        for (TextDisplay t : activeTexts) {
            if (t != null && !t.isDead() && t.isValid()) out.add(t);
        }
        return out;
    }

    @Override
    public void cancel(@NotNull KeyReason reason) {
        if (state != State.RUNNING) return;
        this.state = State.CANCELLED;
        this.cancelReason = reason;
    }

    @Override
    public void tick(long currentTick) {
        if (state != State.RUNNING) return;

        this.lastTick = currentTick;
        if (createdAtTick == 0L) createdAtTick = currentTick;
        long age = currentTick - createdAtTick;

        // Hard session timeout.
        if (age >= settings.sessionTimeoutSeconds() * 20L) {
            if (chosen == null || !exploded) {
                state = State.CANCELLED;
                cancelReason = KeyReason.REFUND_TIMEOUT;
                return;
            }
        }

        // ----- Фаза SPAWNING -----
        if (spawnIndex < totalCreepers) {
            long nextSpawnAt = (long) spawnIndex * settings.spawnIntervalTicks();
            if (age >= nextSpawnAt) {
                spawnOne(spawnIndex);
                spawnIndex++;
                if (spawnIndex == totalCreepers) {
                    spawnCompleteTick = currentTick;
                }
            }
            faceAllAtPlayer(currentTick);
            return;
        }

        // ----- Фаза WAITING_CLICK -----
        if (chosen == null) {
            faceAllAtPlayer(currentTick);
            long waitAge = currentTick - spawnCompleteTick;

            if (!warnedClick
                    && waitAge >= CLICK_WARN_AFTER_SECONDS * 20L) {
                warnedClick = true;
                if (player.isOnline()) {
                    player.showTitle(Title.title(
                            MiniMessageUtil.parse("<red>Выбирай!</red>"),
                            Component.empty(),
                            Title.Times.times(
                                    Duration.ofMillis(150),
                                    Duration.ofMillis(2000),
                                    Duration.ofMillis(300))));
                }
            }

            if (waitAge >= settings.clickTimeoutSeconds() * 20L) {
                // === patch #14: защита от пустого списка ===
                List<Creeper> alive = new ArrayList<>();
                for (Creeper c : creepers) {
                    if (c != null && !c.isDead() && c.isValid()) alive.add(c);
                }
                if (alive.isEmpty()) {
                    state = State.CANCELLED;
                    cancelReason = KeyReason.REFUND_ABORTED;
                    return;
                }
                Creeper pick = alive.get(
                        ThreadLocalRandom.current().nextInt(alive.size()));
                int idx = creepers.indexOf(pick);
                triggerClick(pick, idx >= 0 ? idx : 0, currentTick);
            }
            return;
        }

        // ----- Фазы после клика -----
        long sinceClick = currentTick - clickedTick;

        if (!othersRemoved && sinceClick >= CHARGE_HOLD_TICKS) {
            removeOthersAndReveal();
            othersRemoved = true;
        }

        if (!exploded
                && sinceClick >= CHARGE_HOLD_TICKS + settings.unselectedRevealTicks()) {
            doExplosionAndShowDisplay(currentTick);
            exploded = true;
            displayUntilTick = currentTick + settings.displayTicks();
        }

        if (exploded && displayUntilTick > 0 && currentTick >= displayUntilTick) {
            state = State.COMPLETED;
            return;
        }

        if (exploded && limeDropLoc != null
                && currentTick - lastFlameTick >= 2) {
            lastFlameTick = currentTick;
            drawFlameSquare(limeDropLoc);
        }
    }

    @Override
    public boolean onEntityClick(@NotNull Entity entity, @NotNull Player clicker) {
        if (state != State.RUNNING) return false;
        if (spawnIndex < totalCreepers) return false;
        if (!clicker.getUniqueId().equals(player.getUniqueId())) return false;
        if (chosen != null) return false;
        if (!(entity instanceof Creeper c)) return false;

        int idx = creepers.indexOf(c);
        if (idx < 0) return false;

        long tick = lastTick >= 0 ? lastTick : 0L;
        triggerClick(c, idx, tick);
        return true;
    }

    @Override
    public void cleanup() {
        for (Creeper c : creepers) {
            if (c != null && !c.isDead()) c.remove();
        }
        creepers.clear();

        for (Item i : activeDrops) {
            if (i != null && !i.isDead()) i.remove();
        }
        activeDrops.clear();

        for (TextDisplay t : activeTexts) {
            if (t != null && !t.isDead()) t.remove();
        }
        activeTexts.clear();

        this.limeDropLoc = null;

        World world = blockLocation.getWorld();
        if (world == null) return;
        String sid = sessionId.toString();
        for (Entity e : world.getNearbyEntities(blockLocation, 10, 10, 10)) {
            if (matchesSession(e, META_CREEPER, sid)
                    || matchesSession(e, META_DROP, sid)
                    || matchesSession(e, META_TEXT, sid)) {
                e.remove();
            }
        }
    }

    private static boolean matchesSession(@NotNull Entity e,
                                          @NotNull String key,
                                          @NotNull String sessionId) {
        if (!e.hasMetadata(key)) return false;
        var metas = e.getMetadata(key);
        return !metas.isEmpty() && sessionId.equals(metas.get(0).asString());
    }

    // ------------------------------------------------------------------
    //  Фаза SPAWNING
    // ------------------------------------------------------------------

    private void spawnOne(int index) {
        World world = blockLocation.getWorld();
        if (world == null) return;

        int count = totalCreepers;
        double radius = settings.creeperRadius();

        double centerX = blockLocation.getBlockX() + 0.5;
        double centerZ = blockLocation.getBlockZ() + 0.5;

        // === patch #13: если блок висит в воздухе — ищем землю под ним ===
        double spawnY = blockLocation.getBlockY();
        int maxDrop = 5;
        for (int dy = 1; dy <= maxDrop; dy++) {
            int candidateY = blockLocation.getBlockY() - dy;
            if (candidateY < world.getMinHeight()) break;
            org.bukkit.block.Block b = world.getBlockAt(
                    blockLocation.getBlockX(), candidateY, blockLocation.getBlockZ());
            if (b.getType().isSolid()) {
                spawnY = candidateY + 1;
                break;
            }
        }

        double angle = (2 * Math.PI / count) * index;
        double dx = Math.cos(angle) * radius;
        double dz = Math.sin(angle) * radius;
        Location loc = new Location(world, centerX + dx, spawnY, centerZ + dz);

        faceLocationAtPlayer(loc);

        Creeper c = world.spawn(loc, Creeper.class, creeper -> {
            creeper.setAI(false);
            creeper.setSilent(true);
            // === Этап 10.5: вместо invulnerable — noDamageTicks. ===
            creeper.setMaximumNoDamageTicks(Integer.MAX_VALUE);
            creeper.setNoDamageTicks(Integer.MAX_VALUE);
            creeper.setCollidable(false);
            creeper.setGravity(false);
            creeper.setPersistent(false);
            creeper.setExplosionRadius(0);
            creeper.setMaxFuseTicks(1);
            creeper.setMetadata(META_CREEPER,
                    new FixedMetadataValue(plugin, sessionId.toString()));
        });

        world.spawnParticle(Particle.POOF, loc.clone().add(0, 0.5, 0),
                8, 0.3, 0.3, 0.3, 0.02);

        creepers.add(c);
    }

    private void faceAllAtPlayer(long currentTick) {
        if (currentTick - lastFaceUpdateTick < FACE_UPDATE_INTERVAL) return;
        lastFaceUpdateTick = currentTick;
        for (Creeper c : creepers) {
            if (c == null || c.isDead() || !c.isValid()) continue;
            faceCreeperAtPlayer(c);
        }
    }

    private void faceCreeperAtPlayer(@NotNull Creeper creeper) {
        Location loc = creeper.getLocation().clone();
        faceLocationAtPlayer(loc);
        creeper.setRotation(loc.getYaw(), loc.getPitch());
    }

    private void faceLocationAtPlayer(@NotNull Location from) {
        Location playerLoc = player.getLocation();
        Vector dir = playerLoc.toVector().subtract(from.toVector());
        if (dir.lengthSquared() > 0.0001) {
            from.setDirection(dir);
        }
    }

    // ------------------------------------------------------------------
    //  Клик и фазы после клика
    // ------------------------------------------------------------------

    private void triggerClick(@NotNull Creeper creeper, int index, long tick) {
        if (chosen != null) return;
        this.chosen = creeper;
        this.chosenIndex = index;
        this.clickedTick = tick;

        creeper.setPowered(true);
        creeper.getWorld().playSound(creeper.getLocation(),
                Sound.ENTITY_CREEPER_PRIMED, 1.0f, 1.0f);
    }

    private void removeOthersAndReveal() {
        World world = blockLocation.getWorld();
        if (world == null) return;

        for (int i = 0; i < creepers.size(); i++) {
            if (i == chosenIndex) continue;
            Creeper c = creepers.get(i);
            if (c == null || c.isDead() || !c.isValid()) continue;

            Location at = c.getLocation().clone();
            Reward r = rolledRewards.get(i);

            world.spawnParticle(Particle.POOF, at.clone().add(0, 0.8, 0),
                    10, 0.3, 0.4, 0.3, 0.02);
            world.playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.6f);

            c.remove();

            Location dropLoc = at.clone().add(0, 0.1, 0);
            Item drop = spawnGlintDrop(dropLoc, Material.RED_DYE);
            if (drop != null) activeDrops.add(drop);

            TextDisplay txt = spawnRewardLabel(
                    dropLoc.clone().add(0, 1.0, 0),
                    r.getDisplayName());
            if (txt != null) activeTexts.add(txt);
        }
    }

    private void doExplosionAndShowDisplay(long tick) {
        if (exploded) return;

        clearDropsAndTexts();

        Location boom = chosen != null
                ? chosen.getLocation().clone().add(0, 0.5, 0)
                : blockLocation.clone().add(0.5, 1.0, 0.5);
        World world = boom.getWorld();

        if (chosen != null && !chosen.isDead()) chosen.remove();

        if (world != null) {
            world.spawnParticle(Particle.EXPLOSION_EMITTER, boom, 1, 0, 0, 0, 0);
            world.playSound(boom, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        }

        Reward r = rolledRewards.get(chosenIndex >= 0 ? chosenIndex : 0);

        Location dropLoc = boom.clone().add(0, -0.3, 0);
        this.limeDropLoc = dropLoc.clone();

        Item rewardDrop = spawnGlintDrop(dropLoc, Material.LIME_DYE);
        if (rewardDrop != null) activeDrops.add(rewardDrop);

        TextDisplay txt = spawnRewardLabel(
                dropLoc.clone().add(0, 1.0, 0),
                "<green>" + r.getDisplayName() + "</green>");
        if (txt != null) activeTexts.add(txt);
    }

    private void clearDropsAndTexts() {
        for (Item i : activeDrops) {
            if (i != null && !i.isDead()) i.remove();
        }
        activeDrops.clear();
        for (TextDisplay t : activeTexts) {
            if (t != null && !t.isDead()) t.remove();
        }
        activeTexts.clear();
    }

    // ------------------------------------------------------------------
    //  Огненный квадрат
    // ------------------------------------------------------------------

    private void drawFlameSquare(@NotNull Location center) {
        World world = center.getWorld();
        if (world == null) return;

        double half = 0.6;
        double y = center.getY() + 0.15;
        double step = 0.15;
        int count = (int) Math.round((half * 2) / step);

        for (int i = 0; i <= count; i++) {
            double offset = -half + i * step;
            world.spawnParticle(Particle.FLAME,
                    center.getX() + offset, y, center.getZ() - half,
                    1, 0, 0, 0, 0);
            world.spawnParticle(Particle.FLAME,
                    center.getX() + offset, y, center.getZ() + half,
                    1, 0, 0, 0, 0);
            world.spawnParticle(Particle.FLAME,
                    center.getX() - half, y, center.getZ() + offset,
                    1, 0, 0, 0, 0);
            world.spawnParticle(Particle.FLAME,
                    center.getX() + half, y, center.getZ() + offset,
                    1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------
    //  Вспомогательное
    // ------------------------------------------------------------------

    private @Nullable Item spawnGlintDrop(@NotNull Location loc,
                                          @NotNull Material material) {
        World world = loc.getWorld();
        if (world == null) return null;

        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }

        Item drop = world.dropItem(loc, stack);
        drop.setPickupDelay(Integer.MAX_VALUE);
        drop.setCanMobPickup(false);
        drop.setUnlimitedLifetime(true);
        drop.setGravity(false);
        drop.setVelocity(new Vector(0, 0, 0));
        drop.setMetadata(META_DROP,
                new FixedMetadataValue(plugin, sessionId.toString()));
        // P1-7: drop не сохраняется в регион. Если сервер упадёт посреди
        // анимации, при следующем старте не останется неубираемого LIME_DYE
        // (metadata не переживает рестарт, поэтому cleanup такие drops не найдёт).
        drop.setPersistent(false);
        return drop;
    }

    private @Nullable TextDisplay spawnRewardLabel(@NotNull Location loc,
                                                   @NotNull String text) {
        World world = loc.getWorld();
        if (world == null) return null;

        Component name = MiniMessageUtil.parse(text);
        return world.spawn(loc, TextDisplay.class, d -> {
            d.text(name);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setGravity(false);
            d.setViewRange(1.0f);
            d.setBackgroundColor(Color.fromARGB(180, 0, 0, 0));
            d.setMetadata(META_TEXT,
                    new FixedMetadataValue(plugin, sessionId.toString()));
        });
    }
}