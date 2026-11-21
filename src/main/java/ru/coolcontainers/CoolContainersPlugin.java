package ru.coolcontainers;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.coolcontainers.animations.AnimationService;
import ru.coolcontainers.animations.bee.BeeAnimation;
import ru.coolcontainers.animations.bee.BeeSettings;
import ru.coolcontainers.animations.creeper.CreeperAnimation;
import ru.coolcontainers.animations.creeper.CreeperSettings;
import ru.coolcontainers.animations.treasure.TreasureAnimation;
import ru.coolcontainers.animations.treasure.TreasureSettings;
import ru.coolcontainers.api.CoolContainersAPI;
import ru.coolcontainers.api.KeyGrantResult;
import ru.coolcontainers.api.enums.KeyReason;
import ru.coolcontainers.block.BlockLockService;
import ru.coolcontainers.block.BlockProtectionListener;
import ru.coolcontainers.block.CaseBlockService;
import ru.coolcontainers.block.HologramService;
import ru.coolcontainers.block.KeyLineHologramService;
import ru.coolcontainers.cases.CaseLoader;
import ru.coolcontainers.cases.CaseRegistry;
import ru.coolcontainers.cases.CaseSaver;
import ru.coolcontainers.cases.CaseTemplateGenerator;
import ru.coolcontainers.cases.dto.CaseDefinition;
import ru.coolcontainers.command.CaseAdminCommand;
import ru.coolcontainers.command.CaseCommand;
import ru.coolcontainers.config.Messages;
import ru.coolcontainers.config.PluginConfig;
import ru.coolcontainers.db.Database;
import ru.coolcontainers.db.DbExecutor;
import ru.coolcontainers.db.MigrationManager;
import ru.coolcontainers.db.RetentionService;
import ru.coolcontainers.db.repository.AdminLogRepository;
import ru.coolcontainers.db.repository.BlockRepository;
import ru.coolcontainers.db.repository.CaseHashRepository;
import ru.coolcontainers.db.repository.GrantRepository;
import ru.coolcontainers.db.repository.KeyRepository;
import ru.coolcontainers.db.repository.OpeningRepository;
import ru.coolcontainers.gui.framework.GuiListener;
import ru.coolcontainers.gui.framework.GuiManager;
import ru.coolcontainers.gui.player.CaseMenuGui;
import ru.coolcontainers.integrations.CompositeRewardHooks;
import ru.coolcontainers.integrations.LuckPermsHook;
import ru.coolcontainers.integrations.PlaceholderHook;
import ru.coolcontainers.integrations.PlayerPointsHook;
import ru.coolcontainers.integrations.VaultHook;
import ru.coolcontainers.key.KeyCache;
import ru.coolcontainers.key.KeyService;
import ru.coolcontainers.listener.AnimationDropListener;
import ru.coolcontainers.listener.PlayerInteractListener;
import ru.coolcontainers.open.CaseOpenService;
import ru.coolcontainers.open.OpenRecoveryService;
import ru.coolcontainers.open.RewardGranter;
import ru.coolcontainers.open.RewardHooks;
import ru.coolcontainers.open.RewardRoller;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class CoolContainersPlugin extends JavaPlugin implements CoolContainersAPI {

    // Конфиг и БД
    private PluginConfig pluginConfig;
    private Messages messages;
    private Database database;
    private DbExecutor dbExecutor;
    private RetentionService retentionService;

    // Репозитории
    private KeyRepository keyRepository;
    private OpeningRepository openingRepository;
    private GrantRepository grantRepository;
    private BlockRepository blockRepository;
    private CaseHashRepository caseHashRepository;
    private AdminLogRepository adminLogRepository;

    // Реестр кейсов
    private CaseRegistry caseRegistry;
    private CaseLoader caseLoader;
    private CaseSaver caseSaver;
    private Path casesDir;

    // Интеграции
    private LuckPermsHook luckPermsHook;
    private VaultHook vaultHook;
    private PlayerPointsHook playerPointsHook;
    private PlaceholderHook placeholderHook;

    // Сервисы открытия
    private KeyService keyService;
    private KeyCache keyCache;
    private RewardRoller rewardRoller;
    private RewardHooks rewardHooks;
    private RewardGranter rewardGranter;
    private CaseOpenService caseOpenService;
    private OpenRecoveryService openRecoveryService;

    // Блоки и голограммы
    private HologramService hologramService;
    private CaseBlockService caseBlockService;
    private BlockLockService blockLockService;
    private KeyLineHologramService keyLineHologramService;

    // Анимации
    private AnimationService animationService;

    // GUI
    private GuiManager guiManager;
    private CaseMenuGui caseMenuGui;

    // Команды
    private CaseAdminCommand caseAdminCommand;
    private CaseCommand caseCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");

        this.pluginConfig = PluginConfig.load(this);
        this.messages = Messages.load(this);

        // ---------------- БД ----------------
        try {
            getLogger().info("Прогон миграций БД...");
            MigrationManager.migrate(pluginConfig, this);
            this.database = Database.connect(pluginConfig);
            if (!database.isHealthy()) {
                throw new IllegalStateException("Пул соединений не проходит isValid()");
            }
            getLogger().info("БД подключена. pool=" + pluginConfig.getDbPoolSize());
        } catch (Throwable t) {
            getLogger().severe("Не удалось подключиться к БД: " + t.getMessage());
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ---------------- DbExecutor ----------------
        this.dbExecutor = new DbExecutor(this, pluginConfig.getDbAsyncThreads());

        // ---------------- Кейсы ----------------
        this.caseRegistry = new CaseRegistry();
        this.caseLoader = new CaseLoader(this);
        this.casesDir = getDataFolder().toPath().resolve("cases");
        this.caseSaver = new CaseSaver(this, casesDir);

        new CaseTemplateGenerator(this).ensureDefaults(casesDir);

        CaseLoader.LoadResult result = caseLoader.loadAll(casesDir);
        result.loaded().values().forEach(caseRegistry::register);
        getLogger().info("Загружено кейсов: " + caseRegistry.size()
                + ", ошибок: " + result.errors().size());

        // ---------------- Репозитории ----------------
        this.keyRepository = new KeyRepository(database);
        this.openingRepository = new OpeningRepository(database);
        this.grantRepository = new GrantRepository(database);
        this.blockRepository = new BlockRepository(database);
        this.caseHashRepository = new CaseHashRepository(database);
        this.adminLogRepository = new AdminLogRepository(database);

        syncCaseHashes(result.loaded());

        // ---------------- Retention ----------------
        this.retentionService = new RetentionService(this, database, pluginConfig);

        // ---------------- Сервисы открытия ----------------
        String serverId = pluginConfig.getServerId();

        this.keyService = new KeyService(this, database, keyRepository, serverId);
        this.keyCache = new KeyCache(keyService, dbExecutor);
        getServer().getPluginManager().registerEvents(keyCache, this);

        // ---------------- Интеграции ----------------
        this.luckPermsHook = new LuckPermsHook(this);
        this.vaultHook = new VaultHook(this);
        this.playerPointsHook = new PlayerPointsHook(this);
        this.placeholderHook = new PlaceholderHook(this, keyCache);

        luckPermsHook.init();
        vaultHook.init();
        playerPointsHook.init();
        placeholderHook.init();

        // ---------------- Сервисы открытия ----------------
        this.rewardRoller = new RewardRoller();
        this.rewardHooks = new CompositeRewardHooks(
                this, luckPermsHook, vaultHook, playerPointsHook);
        this.rewardGranter = new RewardGranter(
                this, database, dbExecutor, openingRepository, grantRepository,
                rewardHooks, serverId);
        this.caseOpenService = new CaseOpenService(
                this, database, dbExecutor, caseRegistry, keyRepository,
                openingRepository, serverId);
        this.openRecoveryService = new OpenRecoveryService(
                this, database, dbExecutor, caseRegistry, keyService,
                openingRepository, grantRepository, rewardRoller, rewardGranter, serverId);

        // ---------------- Блоки и голограммы ----------------
        this.hologramService = new HologramService(this);
        this.blockLockService = new BlockLockService(pluginConfig.getBlocksCooldownSeconds());
        this.caseBlockService = new CaseBlockService(
                this, blockRepository, hologramService, caseRegistry, pluginConfig);

        if (caseBlockService.isActive()) {
            caseBlockService.load();
            getServer().getPluginManager().registerEvents(caseBlockService, this);
            getServer().getPluginManager().registerEvents(
                    new BlockProtectionListener(caseBlockService, pluginConfig), this);

            this.keyLineHologramService = new KeyLineHologramService(
                    this, caseBlockService, keyCache);
            caseBlockService.setKeyLineHologramService(keyLineHologramService);
            getServer().getPluginManager().registerEvents(keyLineHologramService, this);
            keyLineHologramService.start();
        }

        // ---------------- Анимации ----------------
        CreeperSettings creeperSettings = CreeperSettings.load(this);
        CreeperAnimation creeperAnimation = new CreeperAnimation(this, creeperSettings);

        BeeSettings beeSettings = BeeSettings.load(this);
        BeeAnimation beeAnimation = new BeeAnimation(this, beeSettings);

        TreasureSettings treasureSettings = TreasureSettings.load(this);
        TreasureAnimation treasureAnimation = new TreasureAnimation(this, treasureSettings);

        this.animationService = new AnimationService(
                this, dbExecutor, rewardRoller, rewardGranter, keyService, blockLockService,
                openingRepository,
                pluginConfig.getMaxConcurrentAnimations(), serverId,
                keyLineHologramService,
                creeperAnimation, beeAnimation, treasureAnimation);
        animationService.start();
        getServer().getPluginManager().registerEvents(animationService, this);
        getServer().getPluginManager().registerEvents(new AnimationDropListener(), this);

        // ---------------- GUI ----------------
        this.guiManager = new GuiManager(this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        this.caseMenuGui = new CaseMenuGui(this, guiManager);

        // ---------------- Команды ----------------
        this.caseAdminCommand = new CaseAdminCommand(this);
        this.caseCommand = new CaseCommand(this);
        registerCommands();

        // ---------------- Слушатели ----------------
        if (caseBlockService.isActive()) {
            getServer().getPluginManager().registerEvents(
                    new PlayerInteractListener(this), this);
        }

        // ---------------- Retention ----------------
        retentionService.start();

        // ---------------- Recovery ----------------
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                openRecoveryService.recover();
            } catch (Throwable t) {
                getLogger().severe("recovery упал: " + t.getMessage());
                t.printStackTrace();
            }
        }, 20L);

        getLogger().info("CoolContainers запущен. server-id=" + serverId);
        getServer().getConsoleSender().sendMessage(
                messages.prefixed("startup.enabled", Map.of("server", serverId))
        );
    }

    @Override
    public void onDisable() {
        if (retentionService != null) retentionService.stop();
        if (keyLineHologramService != null) keyLineHologramService.stop();
        if (animationService != null) animationService.stop();
        if (dbExecutor != null) dbExecutor.shutdown();
        if (database != null) {
            database.close();
            database = null;
        }
        if (messages != null) {
            getServer().getConsoleSender().sendMessage(
                    messages.prefixed("startup.disabled", Map.of())
            );
        }
    }

    // ------------------------------------------------------------------
    //  Регистрация команд через Paper Lifecycle API
    // ------------------------------------------------------------------

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands registrar = event.registrar();

            LiteralArgumentBuilder<CommandSourceStack> admin = LiteralArgumentBuilder
                    .<CommandSourceStack>literal("caseadmin")
                    .requires(src -> src.getSender().hasPermission("cases.admin"))
                    .executes(ctx -> {
                        caseAdminCommand.handle(ctx.getSource().getSender(), new String[0]);
                        return 1;
                    })
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String raw = StringArgumentType.getString(ctx, "args");
                                caseAdminCommand.handle(ctx.getSource().getSender(),
                                        raw.split("\\s+"));
                                return 1;
                            }));

            registrar.register(admin.build(), "CoolContainers admin command");

            LiteralArgumentBuilder<CommandSourceStack> player = LiteralArgumentBuilder
                    .<CommandSourceStack>literal("case")
                    .executes(ctx -> {
                        caseCommand.handle(ctx.getSource().getSender(), new String[0]);
                        return 1;
                    })
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String raw = StringArgumentType.getString(ctx, "args");
                                caseCommand.handle(ctx.getSource().getSender(),
                                        raw.split("\\s+"));
                                return 1;
                            }));

            registrar.register(player.build(), "CoolContainers player command");
        });
    }

    // ------------------------------------------------------------------
    //  Reload
    // ------------------------------------------------------------------

    public void reloadAll() {
        reloadConfig();
        this.pluginConfig = PluginConfig.load(this);
        this.messages = Messages.load(this);

        caseRegistry.clear();
        CaseLoader.LoadResult result = caseLoader.loadAll(casesDir);
        result.loaded().values().forEach(caseRegistry::register);

        syncCaseHashes(result.loaded());

        if (caseBlockService.isActive()) {
            caseBlockService.refreshAllHolograms();
        }
    }

    // ------------------------------------------------------------------
    //  Сохранение кейса
    // ------------------------------------------------------------------

    public boolean saveCase(@NotNull CaseDefinition def) {
        return caseSaver.save(def);
    }

    // ------------------------------------------------------------------
    //  Хэши кейсов
    // ------------------------------------------------------------------

    private void syncCaseHashes(@NotNull Map<String, CaseDefinition> loaded) {
        if (caseHashRepository == null) return;
        String serverId = pluginConfig.getServerId();
        try {
            Map<String, String> existing = caseHashRepository.findAllByServer(serverId);

            for (CaseDefinition def : loaded.values()) {
                String hash = def.getContentHash();
                if (hash == null || hash.isBlank()) continue;
                String prev = existing.get(def.getId());
                if (!hash.equals(prev)) {
                    caseHashRepository.upsert(def.getId(), hash, serverId);
                    if (prev != null) {
                        getLogger().info("Кейс '" + def.getId() + "' изменился: "
                                + prev.substring(0, 8) + " → " + hash.substring(0, 8));
                    }
                }
                existing.remove(def.getId());
            }

            for (String goneId : existing.keySet()) {
                caseHashRepository.delete(goneId, serverId);
                getLogger().info("Кейс '" + goneId + "' больше не загружен — hash убран из БД.");
            }
        } catch (SQLException e) {
            getLogger().warning("Не удалось синхронизировать хэши кейсов: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Публичный API для других плагинов
    // ------------------------------------------------------------------

    @Override
    public boolean hasCase(@NotNull String caseId) {
        return caseRegistry != null && caseRegistry.has(caseId.toLowerCase(Locale.ROOT));
    }

    @Override
    public @NotNull CompletableFuture<KeyGrantResult> giveKeys(@NotNull UUID player,
                                                               @NotNull String caseId,
                                                               int amount,
                                                               @NotNull String sourcePlugin,
                                                               @Nullable UUID sourcePlayer) {
        if (!isEnabled() || dbExecutor == null || keyService == null || caseRegistry == null) {
            return CompletableFuture.completedFuture(
                    KeyGrantResult.fail(KeyGrantResult.Failure.PLUGIN_DISABLED));
        }

        final String cid = caseId.toLowerCase(Locale.ROOT);
        if (amount <= 0) {
            return CompletableFuture.completedFuture(
                    KeyGrantResult.fail(KeyGrantResult.Failure.INVALID_AMOUNT));
        }
        if (!caseRegistry.has(cid)) {
            return CompletableFuture.completedFuture(
                    KeyGrantResult.fail(KeyGrantResult.Failure.UNKNOWN_CASE));
        }

        try {
            return dbExecutor.supply(() -> {
                int newAmount = keyService.addKeys(player, cid, amount,
                        KeyReason.PLUGIN_GIVE, sourcePlayer);
                try {
                    adminLogRepository.logSystem("api.givekeys", Map.of(
                            "plugin", sourcePlugin,
                            "target", player.toString(),
                            "case", cid,
                            "amount", amount,
                            "new", newAmount));
                } catch (SQLException e) {
                    // Ключи уже начислены — аудит не должен откатывать выдачу.
                    getLogger().warning("api.givekeys: не удалось записать аудит: " + e.getMessage());
                }
                return KeyGrantResult.ok(newAmount);
            }).exceptionally(ex -> {
                getLogger().warning("api.givekeys: ошибка выдачи " + player + "/" + cid + ": " + ex);
                return KeyGrantResult.fail(KeyGrantResult.Failure.DB_ERROR);
            });
        } catch (RuntimeException e) {
            // Редкий race с onDisable(): пул уже shutdown.
            return CompletableFuture.completedFuture(
                    KeyGrantResult.fail(KeyGrantResult.Failure.PLUGIN_DISABLED));
        }
    }

    @Override
    public @NotNull CompletableFuture<Integer> getKeys(@NotNull UUID player, @NotNull String caseId) {
        if (!isEnabled() || dbExecutor == null || keyService == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("CoolContainers is not enabled"));
        }
        final String cid = caseId.toLowerCase(Locale.ROOT);
        try {
            return dbExecutor.supply(() -> keyService.getAmount(player, cid));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ------------------------------------------------------------------
    //  Геттеры
    // ------------------------------------------------------------------

    public @NotNull PluginConfig config() { return pluginConfig; }
    public @NotNull Messages messages() { return messages; }
    public @NotNull Database db() {
        if (database == null) throw new IllegalStateException("Database not initialized");
        return database;
    }
    public @NotNull DbExecutor dbExecutor() { return dbExecutor; }
    public @NotNull RetentionService retentionService() { return retentionService; }
    public @NotNull CaseRegistry cases() { return caseRegistry; }
    public @NotNull CaseLoader caseLoader() { return caseLoader; }
    public @NotNull CaseSaver caseSaver() { return caseSaver; }
    public @NotNull Path casesDir() { return casesDir; }
    public @NotNull KeyRepository keyRepository() { return keyRepository; }
    public @NotNull OpeningRepository openingRepository() { return openingRepository; }
    public @NotNull GrantRepository grantRepository() { return grantRepository; }
    public @NotNull BlockRepository blockRepository() { return blockRepository; }
    public @NotNull CaseHashRepository caseHashRepository() { return caseHashRepository; }
    public @NotNull AdminLogRepository adminLogRepository() { return adminLogRepository; }
    public @NotNull KeyService keyService() { return keyService; }
    public @NotNull KeyCache keyCache() { return keyCache; }
    public @NotNull RewardRoller rewardRoller() { return rewardRoller; }
    public @NotNull RewardGranter rewardGranter() { return rewardGranter; }
    public @NotNull CaseOpenService caseOpenService() { return caseOpenService; }
    public @NotNull OpenRecoveryService openRecoveryService() { return openRecoveryService; }
    public @NotNull LuckPermsHook luckPermsHook() { return luckPermsHook; }
    public @NotNull VaultHook vaultHook() { return vaultHook; }
    public @NotNull PlayerPointsHook playerPointsHook() { return playerPointsHook; }
    public @NotNull HologramService hologramService() { return hologramService; }
    public @NotNull CaseBlockService caseBlockService() { return caseBlockService; }
    public @NotNull BlockLockService blockLockService() { return blockLockService; }
    public @Nullable KeyLineHologramService keyLineHologramService() { return keyLineHologramService; }
    public @NotNull AnimationService animationService() { return animationService; }
    public @NotNull GuiManager guiManager() { return guiManager; }
    public @NotNull CaseMenuGui caseMenuGui() { return caseMenuGui; }
    public @NotNull CaseAdminCommand caseAdminCommand() { return caseAdminCommand; }
    public @NotNull CaseCommand caseCommand() { return caseCommand; }

    // ------------------------------------------------------------------
    //  Хелперы
    // ------------------------------------------------------------------

    private void saveResourceIfMissing(@NotNull String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }
}