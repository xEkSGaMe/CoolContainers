package ru.coolcontainers.animations.treasure;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ru.coolcontainers.animations.AnimationContext;
import ru.coolcontainers.animations.AnimationSession;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.block.BlockKey;
import ru.coolcontainers.block.KeyLineHologramService;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.cases.dto.Reward;
import ru.coolcontainers.util.MiniMessageUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Treasure-анимация: блок-счётчик «заряжается» 0→N, затем предмет
 * плавно вылетает из блока и висит с эффектами. Игрок ничего не выбирает.
 *
 * Блок-счётчик, максимальное число зарядов и тайминги конфигурируются
 * через YAML кейса (options animation). Глобальные дефолты — в
 * animations/treasure.yml.
 *
 * Если блок — RespawnAnchor, используется его blockdata.charges.
 * Иначе заряды «виртуальные» (только звуки/партиклы).
 */
public final class TreasureSession implements AnimationSession {

    private static final String META_ITEM = "cc_anim_treasure_item";
    private static final String META_TEXT = "cc_anim_treasure_text";

    private enum Phase { FILLING, WINDUP, EMERGING, DISPLAYING, DONE }

    private final UUID sessionId;
    private final UUID openingId;
    private final Player player;
    private final CaseDefinition definition;
    private final Location blockLocation;
    private final BlockKey blockKey;
    private final TreasureConfig config;
    private final JavaPlugin plugin;
    private final @Nullable KeyLineHologramService keyLineHologramService;

    private final Reward reward;
    private final ItemStack icon;
    private final String label;

    private @Nullable ItemDisplay itemDisplay;
    private @Nullable TextDisplay textDisplay;

    private Phase phase = Phase.FILLING;
    private long createdAtTick = 0L;
    private long phaseStartedTick = 0L;
    private long lastTick = -1L;

    private int currentCharge = 0;
    private boolean blockIsValid = false;
    private boolean anchorIsRespawnAnchor = false;

    private double riseStartY = 0.0;
    private double riseEndY = 0.0;
    private long riseStartedTick = 0L;
    private long displayUntilTick = -1L;

    private State state = State.RUNNING;
    private KeyReason cancelReason;

    public TreasureSession(@NotNull AnimationContext ctx,
                           @NotNull TreasureConfig config,
                           @NotNull JavaPlugin plugin,
                           @Nullable KeyLineHologramService keyLineHologramService) {
        this.sessionId = ctx.sessionId();
        this.openingId = ctx.openingId();
        this.player = ctx.player();
        this.definition = ctx.definition();
        this.blockLocation = ctx.blockLocation();
        this.blockKey = ctx.blockKey();
        this.config = config;
        this.plugin = plugin;
        this.keyLineHologramService = keyLineHologramService;

        this.reward = ctx.roller().roll(definition);
        this.icon = iconFor(reward);
        this.label = reward.getDisplayName();
    }

    @Override public @NotNull UUID sessionId() { return sessionId; }
    @Override public @NotNull UUID openingId() { return openingId; }
    @Override public @NotNull Player player() { return player; }
    @Override public @NotNull CaseDefinition definition() { return definition; }
    @Override public @NotNull Reward reward() { return reward; }
    @Override public @NotNull BlockKey blockKey() { return blockKey; }
    @Override public @NotNull State state() { return state; }
    @Override public @Nullable KeyReason cancelReason() { return cancelReason; }

    @Override
    public @NotNull Collection<Entity> entities() {
        List<Entity> out = new ArrayList<>(2);
        if (itemDisplay != null && !itemDisplay.isDead() && itemDisplay.isValid()) {
            out.add(itemDisplay);
        }
        if (textDisplay != null && !textDisplay.isDead() && textDisplay.isValid()) {
            out.add(textDisplay);
        }
        return out;
    }

    @Override
    public void cancel(@NotNull KeyReason reason) {
        if (state != State.RUNNING) return;
        this.state = State.CANCELLED;
        this.cancelReason = reason;
    }

    /** Клики по нашим дисплеям — гасим (event consume). */
    @Override
    public boolean onEntityClick(@NotNull Entity entity, @NotNull Player clicker) {
        return state == State.RUNNING
                && itemDisplay != null
                && entity.getUniqueId().equals(itemDisplay.getUniqueId());
    }

    // ------------------------------------------------------------------
    //  Тик
    // ------------------------------------------------------------------

    @Override
    public void tick(long currentTick) {
        if (state != State.RUNNING) return;
        this.lastTick = currentTick;

        if (createdAtTick == 0L) {
            createdAtTick = currentTick;
            phaseStartedTick = currentTick;
            initBlock();
        }
        long age = currentTick - createdAtTick;
        if (age >= config.sessionTimeoutSeconds() * 20L) {
            state = State.CANCELLED;
            cancelReason = KeyReason.REFUND_TIMEOUT;
            return;
        }

        switch (phase) {
            case FILLING    -> tickFilling(currentTick);
            case WINDUP     -> tickWindup(currentTick);
            case EMERGING   -> tickEmerging(currentTick);
            case DISPLAYING -> tickDisplaying(currentTick);
            case DONE       -> state = State.COMPLETED;
        }
    }

    // ------------------------------------------------------------------
    //  Инициализация блока
    // ------------------------------------------------------------------

    private void initBlock() {
        World world = blockLocation.getWorld();
        if (world == null) return;

        if (keyLineHologramService != null) {
            keyLineHologramService.suppress(blockKey);
        }

        Block block = world.getBlockAt(blockLocation);
        Material expected = config.blockType();

        if (block.getType() != expected) {
            plugin.getLogger().warning("TreasureSession: блок " + blockKey
                    + " имеет тип " + block.getType()
                    + ", ожидался " + expected
                    + " — заряды будут только визуальными.");
            blockIsValid = false;
            anchorIsRespawnAnchor = false;
            return;
        }

        blockIsValid = true;
        if (block.getBlockData() instanceof RespawnAnchor data) {
            anchorIsRespawnAnchor = true;
            if (data.getCharges() != 0) {
                data.setCharges(0);
                block.setBlockData(data, false);
            }
        } else {
            anchorIsRespawnAnchor = false;
        }
    }

    private void updateAnchorBlockData(int charge) {
        if (!anchorIsRespawnAnchor) return;
        World world = blockLocation.getWorld();
        if (world == null) return;
        Block block = world.getBlockAt(blockLocation);
        if (!(block.getBlockData() instanceof RespawnAnchor data)) return;
        int clamped = Math.max(0, Math.min(4, charge)); // MC hard-limit
        if (data.getCharges() == clamped) return;
        data.setCharges(clamped);
        block.setBlockData(data, false);
    }

    // ------------------------------------------------------------------
    //  Фаза FILLING
    // ------------------------------------------------------------------

    private void tickFilling(long currentTick) {
        long elapsed = currentTick - phaseStartedTick;
        long total = Math.max(1, config.fillTicks());
        double progress = Math.min(1.0, elapsed / (double) total);

        int target = (int) Math.floor(progress * config.maxCharges());
        if (target > config.maxCharges()) target = config.maxCharges();

        while (currentCharge < target) {
            currentCharge++;
            if (blockIsValid && anchorIsRespawnAnchor) {
                updateAnchorBlockData(currentCharge);
            }
            playChargeSound(currentCharge);
        }

        World world = blockLocation.getWorld();
        if (world != null) {
            Location base = blockKey.toLocation(world).add(0, 0.3, 0);
            world.spawnParticle(Particle.WAX_ON, base, 3, 0.3, 0.1, 0.3, 0.02);
            world.spawnParticle(Particle.GLOW, base, 2, 0.2, 0.1, 0.2, 0.01);
        }

        if (progress >= 1.0) {
            phase = Phase.WINDUP;
            phaseStartedTick = currentTick;
        }
    }

    private void playChargeSound(int charge) {
        World world = blockLocation.getWorld();
        if (world == null) return;
        Location loc = blockKey.toLocation(world).add(0, 0.5, 0);
        world.playSound(loc, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                0.7f, 0.8f + charge * 0.15f);
        world.spawnParticle(Particle.END_ROD, loc, 6, 0.15, 0.15, 0.15, 0.02);
    }

    // ------------------------------------------------------------------
    //  Фаза WINDUP
    // ------------------------------------------------------------------

    private void tickWindup(long currentTick) {
        long elapsed = currentTick - phaseStartedTick;
        long total = Math.max(1, config.windupTicks());
        double progress = Math.min(1.0, elapsed / (double) total);

        World world = blockLocation.getWorld();
        if (world != null) {
            double baseX = blockKey.x() + 0.5;
            double baseZ = blockKey.z() + 0.5;
            double baseY = blockKey.y() + 1.2;

            double radius = 2.2 * (1.0 - progress * 0.85);
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(i * 60 + elapsed * 30);
                double px = baseX + Math.cos(a) * radius;
                double pz = baseZ + Math.sin(a) * radius;
                double py = baseY + Math.sin(elapsed * 0.3 + i) * 0.25;
                world.spawnParticle(Particle.END_ROD, px, py, pz, 1, 0, 0, 0, 0.01);
            }
            world.spawnParticle(Particle.WAX_ON, baseX, baseY, baseZ,
                    3, 0.1, 0.1, 0.1, 0.02);

            if (elapsed % 3 == 0) {
                world.playSound(blockKey.toLocation(world),
                        Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f,
                        (float) (0.8 + 1.0 * progress));
            }
        }

        if (progress >= 1.0) {
            phase = Phase.EMERGING;
            startEmerging(currentTick);
        }
    }

    // ------------------------------------------------------------------
    //  Фаза EMERGING
    // ------------------------------------------------------------------

    private void startEmerging(long currentTick) {
        World world = blockLocation.getWorld();
        if (world == null) { phase = Phase.DONE; return; }

        double baseX = blockKey.x() + 0.5;
        double baseZ = blockKey.z() + 0.5;
        this.riseStartY = blockKey.y() + 1.0;
        this.riseEndY = riseStartY + config.riseHeight();
        this.riseStartedTick = currentTick;

        Location spawnLoc = new Location(world, baseX, riseStartY, baseZ);

        this.itemDisplay = world.spawn(spawnLoc, ItemDisplay.class, d -> {
            d.setItemStack(icon);
            d.setBillboard(Display.Billboard.CENTER);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setGravity(false);
            d.setViewRange(1.0f);
            d.setTransformation(buildTransform(0.3f, 0f));
            d.setMetadata(META_ITEM, new FixedMetadataValue(plugin, sessionId.toString()));
        });

        this.textDisplay = world.spawn(spawnLoc.clone().add(0, 0.8, 0),
                TextDisplay.class, d -> {
                    d.text(MiniMessageUtil.parse(label));
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

        world.playSound(spawnLoc, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.4f);
        world.playSound(spawnLoc, Sound.ENTITY_ENDER_EYE_LAUNCH, 0.8f, 0.9f);
        world.spawnParticle(Particle.FIREWORK, spawnLoc, 30, 0.4, 0.2, 0.4, 0.15);
        world.spawnParticle(Particle.END_ROD, spawnLoc, 20, 0.3, 0.3, 0.3, 0.05);
    }

    private void tickEmerging(long currentTick) {
        if (itemDisplay == null || itemDisplay.isDead()) { phase = Phase.DONE; return; }

        long elapsed = currentTick - riseStartedTick;
        long total = Math.max(1, config.riseTicks());
        double p = Math.min(1.0, elapsed / (double) total);
        double eased = p * p * (3.0 - 2.0 * p);

        double y = riseStartY + (riseEndY - riseStartY) * eased;
        Location loc = itemDisplay.getLocation();
        loc.setY(y);
        itemDisplay.teleport(loc);
        itemDisplay.setTransformation(buildTransform((float) (0.3 + 0.7 * eased), 0f));

        if (textDisplay != null && !textDisplay.isDead()) {
            Location tl = textDisplay.getLocation();
            tl.setY(y + 0.8);
            textDisplay.teleport(tl);
        }

        World world = loc.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.END_ROD, loc, 3, 0.15, 0.1, 0.15, 0.02);
            world.spawnParticle(Particle.WAX_ON, loc, 2, 0.2, 0.1, 0.2, 0.02);
            if (elapsed % 4 == 0) {
                world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.3f,
                        (float) (0.8 + 1.2 * p));
            }
        }

        if (p >= 1.0) {
            phase = Phase.DISPLAYING;
            phaseStartedTick = currentTick;
            displayUntilTick = currentTick + config.displayTicks();

            if (world != null) {
                world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.7f, 1.0f);
                world.playSound(loc, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.3f);
                world.spawnParticle(Particle.FIREWORK, loc, 40, 0.5, 0.5, 0.5, 0.2);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 20, 0.4, 0.4, 0.4, 0.3);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Фаза DISPLAYING
    // ------------------------------------------------------------------

    private void tickDisplaying(long currentTick) {
        if (itemDisplay == null || itemDisplay.isDead()) { phase = Phase.DONE; return; }

        long elapsed = currentTick - phaseStartedTick;
        if (elapsed % 2 == 0) {
            float angle = (float) ((elapsed * 4) * Math.PI / 180.0);
            float pulse = 1.0f + (float) (0.04 * Math.sin(elapsed * 0.15));
            itemDisplay.setTransformation(buildTransform(pulse, angle));
        }

        World world = itemDisplay.getWorld();
        if (world != null && elapsed % 3 == 0) {
            Location loc = itemDisplay.getLocation();
            double radius = 0.55;
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(i * 90 + elapsed * 12);
                world.spawnParticle(Particle.FLAME,
                        loc.getX() + Math.cos(a) * radius,
                        loc.getY(),
                        loc.getZ() + Math.sin(a) * radius,
                        1, 0, 0.01, 0, 0.0);
            }
        }

        if (displayUntilTick > 0 && currentTick >= displayUntilTick) {
            phase = Phase.DONE;
        }
    }

    // ------------------------------------------------------------------
    //  Cleanup
    // ------------------------------------------------------------------

    @Override
    public void cleanup() {
        if (itemDisplay != null && !itemDisplay.isDead()) itemDisplay.remove();
        if (textDisplay != null && !textDisplay.isDead()) textDisplay.remove();
        itemDisplay = null;
        textDisplay = null;

        // Сброс счётчика
        if (blockIsValid && anchorIsRespawnAnchor) {
            updateAnchorBlockData(0);
        }
        currentCharge = 0;

        if (keyLineHologramService != null) {
            keyLineHologramService.unsuppress(blockKey);
        }

        World world = blockLocation.getWorld();
        if (world == null) return;
        String sid = sessionId.toString();
        for (Entity e : world.getNearbyEntities(blockLocation, 10, 10, 10)) {
            if (matchesSession(e, META_ITEM, sid) || matchesSession(e, META_TEXT, sid)) {
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
    //  Трансформация + иконки
    // ------------------------------------------------------------------

    private static @NotNull Transformation buildTransform(float scale, float yaw) {
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(0f, 0f, 1f, 0f),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(yaw, 0f, 1f, 0f)
        );
    }

    private static @NotNull ItemStack iconFor(@NotNull Reward reward) {
        if (!reward.getItems().isEmpty()) return reward.getItems().get(0).clone();
        if (reward.getLuckPerms() != null) return new ItemStack(Material.NETHER_STAR);
        if (reward.getVault() != null) return new ItemStack(Material.GOLD_INGOT);
        if (reward.getPlayerPoints() != null) return new ItemStack(Material.EMERALD);
        return new ItemStack(Material.PAPER);
    }
}