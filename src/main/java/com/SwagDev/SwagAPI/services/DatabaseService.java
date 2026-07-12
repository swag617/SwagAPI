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

    @Override
    public void executeAsync(Runnable task) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public <T> CompletableFuture<T> queryAsync(Callable<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
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
