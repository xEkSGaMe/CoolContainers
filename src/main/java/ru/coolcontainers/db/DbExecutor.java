package ru.coolcontainers.db;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Пул для JDBC + хелпер для возврата на main-поток.
 *
 * Правила:
 *  - Все SQL-операции — через supply/run (уходят в пул).
 *  - Любые вызовы Bukkit API из callback'ов — через onMain.
 *  - shutdown() вызывается в onDisable ПОСЛЕ того, как все сервисы
 *    завершили работу, и ДО database.close().
 */
public final class DbExecutor {

    private final JavaPlugin plugin;
    private final ExecutorService pool;

    public DbExecutor(@NotNull JavaPlugin plugin, int threads) {
        this.plugin = plugin;
        int n = Math.max(1, threads);
        this.pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "CoolContainers-DB");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thr, ex) ->
                    plugin.getLogger().log(Level.SEVERE, "DB worker uncaught", ex));
            return t;
        });
    }

    public @NotNull <T> CompletableFuture<T> supply(@NotNull SqlSupplier<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, pool);
    }

    public @NotNull CompletableFuture<Void> run(@NotNull SqlRunnable work) {
        return CompletableFuture.runAsync(() -> {
            try {
                work.run();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, pool);
    }

    /** Гарантированно выполняет action на main-потоке. */
    public void onMain(@NotNull Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface SqlSupplier<T> { T get() throws SQLException; }

    @FunctionalInterface
    public interface SqlRunnable { void run() throws SQLException; }
}