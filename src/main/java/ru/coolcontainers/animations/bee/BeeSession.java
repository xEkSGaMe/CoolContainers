package ru.coolcontainers.animations.bee;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Bee;
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

public final class BeeSession implements AnimationSession {

    private static final int CHARGE_HOLD_TICKS = 20;
    private static final int CLICK_WARN_AFTER_SECONDS = 5;
    private static final int GROUND_SEARCH_DEPTH = 5;
    private static final int DIRECTION_LOOKAHEAD = 3;

    private static final String META_BEE  = "cc_anim_bee";
    private static final String META_DROP = "cc_anim_drop";
    private static final String META_TEXT = "cc_anim_text";

    private final UUID sessionId;
    private final UUID openingId;
    private final Player player;
    private final CaseDefinition definition;
    private final Location blockLocation;
    private final BlockKey blockKey;
    private final BeeSettings settings;
    private final JavaPlugin plugin;
    private final int totalBees;

    private final List<Reward> rolledRewards = new ArrayList<>();
    private final List<Bee> bees = new ArrayList<>();
    private final List<Long> beeSpawnTicks = new ArrayList<>();
    private final List<Double> beeBaseAngles = new ArrayList<>();
    private final List<Item> activeDrops = new ArrayList<>();
    private final List<TextDisplay> activeTexts = new ArrayList<>();

    private int spawnIndex = 0;
    private long spawnCompleteTick = -1;

    private int chosenIndex = -1;
    private Bee chosen;
    private long clickedTick = -1;
    private boolean othersRemoved = false;
    private boolean killed = false;
    private long displayUntilTick = -1;

    private boolean warnedClick = false;
    private @Nullable Location limeDropLoc;
    private long lastFlameTick = 0;

    private long createdAtTick = 0L;
    private long lastTick = -1L;

    private State state = State.RUNNING;
    private KeyReason cancelReason;

    private @Nullable World world;
    private double centerX;
    private double centerZ;
    private double blockTopY;
    private double flightStartY;
    private double groundY;
    private boolean geometryReady = false;

    public BeeSession(@NotNull AnimationContext ctx,
                      @NotNull BeeSettings settings,
                      @NotNull JavaPlugin plugin) {
        this.sessionId = ctx.sessionId();
        this.openingId = ctx.openingId();
        this.player = ctx.player();
        this.definition = ctx.definition();
        this.blockLocation = ctx.blockLocation();
        this.blockKey = ctx.blockKey();
        this.settings = settings;
        this.plugin = plugin;
        this.totalBees = Math.max(1, settings.beeCount());

        RewardRoller roller = ctx.roller();
        for (int i = 0; i < totalBees; i++) {
            rolledRewards.add(roller.roll(definition));
        }
    }

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
        List<Entity> out = new ArrayList<>();
        for (Bee b : bees) if (b != null && !b.isDead() && b.isValid()) out.add(b);
        for (Item i : activeDrops) if (i != null && !i.isDead() && i.isValid()) out.add(i);
        for (TextDisplay t : activeTexts) if (t != null && !t.isDead() && t.isValid()) out.add(t);
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
        if (createdAtTick == 0L) {
            createdAtTick = currentTick;
            initGeometry();
        }
        long age = currentTick - createdAtTick;

        if (!geometryReady) {
            state = State.CANCELLED;
            cancelReason = KeyReason.REFUND_ABORTED;
            return;
        }

        if (age >= settings.sessionTimeoutSeconds() * 20L) {
            if (chosen == null || !killed) {
                state = State.CANCELLED;
                cancelReason = KeyReason.REFUND_TIMEOUT;
                return;
            }
        }

        if (spawnIndex < totalBees) {
            long nextSpawnAt = (long) spawnIndex * settings.spawnIntervalTicks();
            if (age >= nextSpawnAt) {
                spawnOne(spawnIndex, currentTick);
                spawnIndex++;
                if (spawnIndex == totalBees) {
                    spawnCompleteTick = currentTick;
                }
            }
            updateBeePositions(currentTick);
            return;
        }

        if (chosen == null) {
            updateBeePositions(currentTick);
            long waitAge = currentTick - spawnCompleteTick;

            if (!warnedClick && waitAge >= CLICK_WARN_AFTER_SECONDS * 20L) {
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
                List<Bee> alive = new ArrayList<>();
                List<Integer> aliveIdx = new ArrayList<>();
                for (int i = 0; i < bees.size(); i++) {
                    Bee b = bees.get(i);
                    if (b != null && !b.isDead() && b.isValid()) {
                        alive.add(b);
                        aliveIdx.add(i);
                    }
                }
                if (alive.isEmpty()) {
                    state = State.CANCELLED;
                    cancelReason = KeyReason.REFUND_ABORTED;
                    return;
                }
                int pick = ThreadLocalRandom.current().nextInt(alive.size());
                triggerClick(alive.get(pick), aliveIdx.get(pick), currentTick);
            }
            return;
        }

        updateBeePositions(currentTick);
        long sinceClick = currentTick - clickedTick;

        if (!othersRemoved && sinceClick >= CHARGE_HOLD_TICKS) {
            removeOthersAndReveal();
            othersRemoved = true;
        }

        if (!killed && sinceClick >= CHARGE_HOLD_TICKS + settings.unselectedRevealTicks()) {
            doKillAndShowDisplay();
            killed = true;
            displayUntilTick = currentTick + settings.displayTicks();
        }

        if (killed && displayUntilTick > 0 && currentTick >= displayUntilTick) {
            state = State.COMPLETED;
            return;
        }

        if (killed && limeDropLoc != null && currentTick - lastFlameTick >= 2) {
            lastFlameTick = currentTick;
            drawFlameSquare(limeDropLoc);
        }
    }

    @Override
    public boolean onEntityClick(@NotNull Entity entity, @NotNull Player clicker) {
        if (state != State.RUNNING) return false;
        if (spawnIndex < totalBees) return false;
        if (!clicker.getUniqueId().equals(player.getUniqueId())) return false;
        if (chosen != null) return false;
        if (!(entity instanceof Bee b)) return false;

        int idx = bees.indexOf(b);
        if (idx < 0) return false;

        long tick = lastTick >= 0 ? lastTick : 0L;
        triggerClick(b, idx, tick);
        return true;
    }

    @Override
    public void cleanup() {
        for (Bee b : bees) if (b != null && !b.isDead()) b.remove();
        bees.clear();
        beeSpawnTicks.clear();
        beeBaseAngles.clear();

        for (Item i : activeDrops) if (i != null && !i.isDead()) i.remove();
        activeDrops.clear();

        for (TextDisplay t : activeTexts) if (t != null && !t.isDead()) t.remove();
        activeTexts.clear();

        this.limeDropLoc = null;

        World w = world != null ? world : blockLocation.getWorld();
        if (w == null) return;
        String sid = sessionId.toString();
        for (Entity e : w.getNearbyEntities(blockLocation, 10, 10, 10)) {
            if (matchesSession(e, META_BEE, sid) || matchesSession(e, META_DROP, sid) || matchesSession(e, META_TEXT, sid)) {
                e.remove();
            }
        }
    }

    private static boolean matchesSession(@NotNull Entity e, @NotNull String key, @NotNull String sessionId) {
        if (!e.hasMetadata(key)) return false;
        var metas = e.getMetadata(key);
        return !metas.isEmpty() && sessionId.equals(metas.get(0).asString());
    }

    private void initGeometry() {
        this.world = blockLocation.getWorld();
        if (world == null) return;

        this.centerX = blockLocation.getBlockX() + 0.5;
        this.centerZ = blockLocation.getBlockZ() + 0.5;
        this.blockTopY = blockLocation.getBlockY() + 1.0;
        this.flightStartY = blockLocation.getBlockY() + 0.5;

        double gy = blockLocation.getBlockY();
        for (int dy = 1; dy <= GROUND_SEARCH_DEPTH; dy++) {
            int candidateY = blockLocation.getBlockY() - dy;
            if (candidateY < world.getMinHeight()) break;
            Block b = world.getBlockAt(blockLocation.getBlockX(), candidateY, blockLocation.getBlockZ());
            if (b.getType().isSolid()) {
                gy = candidateY + 1;
                break;
            }
        }
        this.groundY = gy;
        this.geometryReady = true;
    }

    private void spawnOne(int index, long currentTick) {
        if (world == null) return;

        Location spawnLoc = new Location(world, centerX, flightStartY, centerZ);
        double baseAngle = (2 * Math.PI / totalBees) * index;

        spawnLoc.setDirection(new Vector(Math.cos(baseAngle), 0, Math.sin(baseAngle)));

        Bee bee = world.spawn(spawnLoc, Bee.class, b -> {
            b.setAI(false);
            b.setSilent(true);
            b.setMaximumNoDamageTicks(Integer.MAX_VALUE);
            b.setNoDamageTicks(Integer.MAX_VALUE);
            b.setCollidable(false);
            b.setGravity(false);
            b.setPersistent(false);
            b.setAnger(0);
            b.setCannotEnterHiveTicks(Integer.MAX_VALUE);
            b.setHasStung(false);
            b.setMetadata(META_BEE, new FixedMetadataValue(plugin, sessionId.toString()));
        });

        double ringR = 0.55;
        int ringPoints = 12;
        for (int k = 0; k < ringPoints; k++) {
            double a = (2 * Math.PI * k) / ringPoints;
            double rx = centerX + Math.cos(a) * ringR;
            double rz = centerZ + Math.sin(a) * ringR;
            world.spawnParticle(Particle.CRIT, rx, blockTopY + 0.05, rz, 1, 0, 0.02, 0, 0.05);
        }
        world.spawnParticle(Particle.WAX_ON, centerX, blockTopY + 0.15, centerZ, 8, 0.4, 0.15, 0.4, 0.0);
        world.spawnParticle(Particle.CLOUD, spawnLoc, 5, 0.12, 0.12, 0.12, 0.02);
        world.playSound(spawnLoc, Sound.ENTITY_BEE_LOOP, 0.6f, 1.2f);

        bees.add(bee);
        beeSpawnTicks.add(currentTick);
        beeBaseAngles.add(baseAngle);
    }

    private @NotNull Location computeBeePos(int i, long currentTick) {
        long spawnTick = beeSpawnTicks.get(i);
        long tLocal = currentTick - spawnTick;
        double baseAngle = beeBaseAngles.get(i);

        final double outerRadius = settings.orbitRadius();
        final double innerRadius = outerRadius * settings.innerRadiusFactor();
        final int flightTicks = settings.flightTicks();
        final int innerTicks = settings.innerOrbitTicks();
        final int expandTicks = settings.expandTicks();
        final double orbitSpeed = settings.orbitSpeed();
        final double baseOrbitY = blockLocation.getBlockY() + settings.hoverHeight();

        // МЕНЯЕМ ЗНАК НА МИНУС: теперь кольцо крутится ПО ЧАСОВОЙ, и Bee 1 всегда впереди!
        double orbitAngle = baseAngle - (currentTick - createdAtTick) * orbitSpeed;
        double cosA = Math.cos(orbitAngle);
        double sinA = Math.sin(orbitAngle);

        if (tLocal < flightTicks) {
            double progress = Math.max(0.0, tLocal / (double) flightTicks);
            double eased = progress * progress * (3.0 - 2.0 * progress);
            double r = innerRadius * eased;

            // ФИКС ПОЛЕТА: интерполируем угол, чтобы не летели задом
            double flightAngle = baseAngle + (orbitAngle - baseAngle) * eased;
            double x = centerX + Math.cos(flightAngle) * r;
            double z = centerZ + Math.sin(flightAngle) * r;
            double y = flightStartY + (baseOrbitY - flightStartY) * eased + Math.sin(progress * Math.PI) * 0.35;
            return new Location(world, x, y, z);
        }

        long postFlight = tLocal - flightTicks;
        if (postFlight < innerTicks) {
            double bob = Math.sin(postFlight * 0.15) * 0.10;
            return new Location(world, centerX + cosA * innerRadius, baseOrbitY + bob, centerZ + sinA * innerRadius);
        }

        long afterInner = postFlight - innerTicks;
        if (afterInner < expandTicks) {
            double progress = afterInner / (double) expandTicks;
            double eased = progress * progress * (3.0 - 2.0 * progress);
            double r = innerRadius + (outerRadius - innerRadius) * eased;
            double bob = Math.sin(postFlight * 0.15) * 0.12;
            return new Location(world, centerX + cosA * r, baseOrbitY + bob, centerZ + sinA * r);
        }

        double bob = Math.sin(postFlight * 0.15) * 0.15;
        return new Location(world, centerX + cosA * outerRadius, baseOrbitY + bob, centerZ + sinA * outerRadius);
    }

    private void updateBeePositions(long currentTick) {
        if (world == null) return;

        for (int i = 0; i < bees.size(); i++) {
            Bee bee = bees.get(i);
            if (bee == null || bee.isDead() || !bee.isValid()) continue;

            Location now = computeBeePos(i, currentTick);
            Location ahead = computeBeePos(i, currentTick + DIRECTION_LOOKAHEAD);

            Vector dir = ahead.toVector().subtract(now.toVector());
            dir.setY(0);
            if (dir.lengthSquared() > 1.0E-6) {
                now.setDirection(dir);
            }
            bee.teleport(now);
        }
    }

    private void triggerClick(@NotNull Bee bee, int index, long tick) {
        if (chosen != null) return;
        this.chosen = bee;
        this.chosenIndex = index;
        this.clickedTick = tick;

        bee.setAnger(800);
        World w = world != null ? world : bee.getWorld();
        w.playSound(bee.getLocation(), Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 1.0f, 1.0f);
        w.spawnParticle(Particle.ANGRY_VILLAGER, bee.getLocation().add(0, 0.6, 0), 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.CRIT, bee.getLocation(), 10, 0.3, 0.3, 0.3, 0.15);
    }

    private void removeOthersAndReveal() {
        if (world == null) return;
        for (int i = 0; i < bees.size(); i++) {
            if (i == chosenIndex) continue;
            Bee b = bees.get(i);
            if (b == null || b.isDead() || !b.isValid()) continue;

            Location at = b.getLocation().clone();
            Reward r = rolledRewards.get(i);

            world.spawnParticle(Particle.CRIT, at, 16, 0.3, 0.3, 0.3, 0.25);
            world.playSound(at, Sound.ENTITY_BEE_DEATH, 0.7f, 1.1f);

            b.remove();

            Location dropLoc = new Location(world, at.getX(), groundY + 0.1, at.getZ());
            Item drop = spawnGlintDrop(dropLoc, Material.RED_DYE);
            if (drop != null) activeDrops.add(drop);

            TextDisplay txt = spawnRewardLabel(dropLoc.clone().add(0, 1.0, 0), r.getDisplayName());
            if (txt != null) activeTexts.add(txt);
        }
    }

    private void doKillAndShowDisplay() {
        if (killed) return;
        clearDropsAndTexts();

        Location killLoc = chosen != null ? chosen.getLocation().clone() : new Location(world, centerX, blockTopY + 0.5, centerZ);
        World w = world != null ? world : killLoc.getWorld();
        if (w == null) return;

        if (chosen != null && !chosen.isDead()) {
            w.spawnParticle(Particle.EXPLOSION, killLoc, 1, 0, 0, 0, 0);
            w.playSound(killLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
            chosen.remove();
        }

        Reward r = rolledRewards.get(chosenIndex >= 0 ? chosenIndex : 0);
        Location dropLoc = new Location(w, killLoc.getX(), groundY + 0.1, killLoc.getZ());
        this.limeDropLoc = dropLoc.clone();

        Item rewardDrop = spawnGlintDrop(dropLoc, Material.LIME_DYE);
        if (rewardDrop != null) activeDrops.add(rewardDrop);

        TextDisplay txt = spawnRewardLabel(dropLoc.clone().add(0, 1.0, 0), "<green>" + r.getDisplayName() + "</green>");
        if (txt != null) activeTexts.add(txt);
    }

    private void clearDropsAndTexts() {
        for (Item i : activeDrops) if (i != null && !i.isDead()) i.remove();
        activeDrops.clear();
        for (TextDisplay t : activeTexts) if (t != null && !t.isDead()) t.remove();
        activeTexts.clear();
    }

    private void drawFlameSquare(@NotNull Location center) {
        World w = center.getWorld();
        if (w == null) return;
        double half = 0.6, y = center.getY() + 0.15, step = 0.15;
        int count = (int) Math.round((half * 2) / step);
        for (int i = 0; i <= count; i++) {
            double offset = -half + i * step;
            w.spawnParticle(Particle.FLAME, center.getX() + offset, y, center.getZ() - half, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.FLAME, center.getX() + offset, y, center.getZ() + half, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.FLAME, center.getX() - half, y, center.getZ() + offset, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.FLAME, center.getX() + half, y, center.getZ() + offset, 1, 0, 0, 0, 0);
        }
    }

    private @Nullable Item spawnGlintDrop(@NotNull Location loc, @NotNull Material material) {
        World w = loc.getWorld();
        if (w == null) return null;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        Item drop = w.dropItem(loc, stack);
        drop.setPickupDelay(Integer.MAX_VALUE);
        drop.setCanMobPickup(false);
        drop.setUnlimitedLifetime(true);
        drop.setGravity(false);
        drop.setVelocity(new Vector(0, 0, 0));
        drop.setMetadata(META_DROP, new FixedMetadataValue(plugin, sessionId.toString()));
        // P1-7: drop не сохраняется в регион (см. CreeperSession.spawnGlintDrop).
        drop.setPersistent(false);
        return drop;
    }

    private @Nullable TextDisplay spawnRewardLabel(@NotNull Location loc, @NotNull String text) {
        World w = loc.getWorld();
        if (w == null) return null;
        Component name = MiniMessageUtil.parse(text);
        return w.spawn(loc, TextDisplay.class, d -> {
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
            d.setMetadata(META_TEXT, new FixedMetadataValue(plugin, sessionId.toString()));
        });
    }
}