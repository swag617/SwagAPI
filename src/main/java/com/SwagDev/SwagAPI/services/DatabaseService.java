package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IDatabaseService;
import com.SwagDev.SwagAPI.database.DatabaseManager;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public class DatabaseService implements IDatabaseService {

    private final SwagAPI plugin;
    private HikariDataSource dataSource;
    private String dbType;

    public DatabaseService(SwagAPI plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        dbType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        plugin.getDataFolder().mkdirs();
        dataSource = DatabaseManager.buildPool(plugin);
        createTables();
        plugin.getLogger().info("Database initialized (" + dbType + ").");
    }

    private void createTables() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS swagapi_players (" +
                "  uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "  username VARCHAR(16) NOT NULL," +
                "  first_join BIGINT NOT NULL," +
                "  last_seen BIGINT NOT NULL" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS swagapi_modules (" +
                "  uuid VARCHAR(36) NOT NULL," +
                "  plugin_key VARCHAR(64) NOT NULL," +
                "  json_data TEXT NOT NULL," +
                "  PRIMARY KEY (uuid, plugin_key)" +
                ")"
            );
            // Backs OverflowService (IOverflowService) — the ecosystem-wide overflow inbox.
            // A per-entry random-UUID id (rather than an auto-increment column) sidesteps the
            // MySQL/SQLite AUTO_INCREMENT syntax difference entirely, matching the id style
            // already used for swagapi_players' primary key.
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS swagapi_overflow_items (" +
                "  id VARCHAR(36) NOT NULL PRIMARY KEY," +
                "  uuid VARCHAR(36) NOT NULL," +
                "  source_plugin VARCHAR(64) NOT NULL," +
                "  item_data TEXT NOT NULL," +
                "  stored_at BIGINT NOT NULL" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_swagapi_overflow_uuid ON swagapi_overflow_items(uuid)"
            );
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create database tables: " + e.getMessage());
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public HikariDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public boolean isMySQL() {
        return "mysql".equals(dbType);
    }

    @Override
    public boolean isSQLite() {
        return "sqlite".equals(dbType);
    }

    /**
     * Runs {@code task} on an async scheduler thread. If SwagAPI is disabling (or already
     * disabled) when this is called, the scheduler rejects new task registrations, so we
     * fall back to running the task synchronously on the calling thread instead. This matters
     * because {@link PlayerDataService#saveAll()} (the shutdown flush) calls dependent
     * plugins' {@code PlayerDataModule#save(...)} implementations directly, and those
     * implementations commonly go through this method to persist their data — without this
     * fallback, any such module would fail its final save with
     * "Plugin attempted to register task while disabled" during every server shutdown.
     */
    @Override
    public void executeAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            task.run();
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    /** See {@link #executeAsync(Runnable)} for why the disabled-plugin fallback exists. */
    @Override
    public <T> CompletableFuture<T> queryAsync(Callable<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            try {
                future.complete(query.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                future.complete(query.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
