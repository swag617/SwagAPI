package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IPlayerDataService;
import com.SwagDev.SwagAPI.events.SwagPlayerDataLoadEvent;
import com.SwagDev.SwagAPI.events.SwagPlayerDataSaveEvent;
import com.SwagDev.SwagAPI.model.PlayerDataModule;
import com.SwagDev.SwagAPI.model.SwagPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataService implements IPlayerDataService {

    private final SwagAPI plugin;
    private final DatabaseService db;
    private final ConcurrentHashMap<UUID, SwagPlayerProfile> profileCache = new ConcurrentHashMap<>();
    private final Map<String, PlayerDataModule> modules = new ConcurrentHashMap<>();
    private BukkitTask autoSaveTask;

    public PlayerDataService(SwagAPI plugin, DatabaseService db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void initialize() {
        int intervalMinutes = plugin.getConfig().getInt("player-data.auto-save-interval-minutes", 5);
        long intervalTicks = intervalMinutes * 60L * 20L;
        autoSaveTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (UUID uuid : profileCache.keySet()) {
                saveProfileInternal(uuid);
            }
        }, intervalTicks, intervalTicks);
    }

    @Override
    public CompletableFuture<SwagPlayerProfile> loadProfile(UUID uuid) {
        CompletableFuture<SwagPlayerProfile> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SwagPlayerProfile profile = fetchOrCreate(uuid);
                profileCache.put(uuid, profile);
                List<CompletableFuture<?>> moduleFutures = new ArrayList<>();
                for (Map.Entry<String, PlayerDataModule> entry : modules.entrySet()) {
                    String key = entry.getKey();
                    PlayerDataModule module = entry.getValue();
                    CompletableFuture<Object> mf = module.load(uuid, db);
                    mf.thenAccept(data -> {
                        if (data != null) {
                            profile.setModuleData(key, data);
                        }
                    });
                    moduleFutures.add(mf);
                }
                CompletableFuture.allOf(moduleFutures.toArray(new CompletableFuture[0])).join();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getServer().getPluginManager().callEvent(
                                new SwagPlayerDataLoadEvent(uuid, profile)));
                future.complete(profile);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load profile for " + uuid + ": " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private SwagPlayerProfile fetchOrCreate(UUID uuid) throws Exception {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT username, first_join, last_seen FROM swagapi_players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SwagPlayerProfile(
                            uuid,
                            rs.getString("username"),
                            rs.getLong("first_join"),
                            rs.getLong("last_seen")
                    );
                }
            }
        }
        long now = System.currentTimeMillis();
        Player online = plugin.getServer().getPlayer(uuid);
        String name = online != null ? online.getName() : uuid.toString().substring(0, 8);
        SwagPlayerProfile profile = new SwagPlayerProfile(uuid, name, now, now);
        upsertPlayer(profile);
        return profile;
    }

    @Override
    public CompletableFuture<Void> saveProfile(UUID uuid) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                saveProfileInternal(uuid);
                future.complete(null);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to save profile for " + uuid + ": " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * @return true if the profile (and all module data) saved without error, false otherwise.
     */
    private boolean saveProfileInternal(UUID uuid) {
        SwagPlayerProfile profile = profileCache.get(uuid);
        if (profile == null) return false;
        try {
            upsertPlayer(profile);
            for (Map.Entry<String, PlayerDataModule> entry : modules.entrySet()) {
                Object data = profile.getModuleData(entry.getKey());
                if (data != null) {
                    entry.getValue().save(uuid, data, db).join();
                }
            }
            fireSaveEvent(uuid, profile);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Error during profile save for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Fires {@link SwagPlayerDataSaveEvent} for a completed save.
     * <p>
     * The periodic auto-save timer and {@link #saveProfile(UUID)} call this from an async
     * scheduler thread, so the event is normally dispatched via {@code runTask} to hop back
     * onto the main thread (Bukkit events must fire synchronously on the primary thread).
     * <p>
     * {@link #saveAll()} (the shutdown flush) calls this directly from the main thread while
     * {@code onDisable()} is executing. At that point {@code plugin.isEnabled()} is already
     * false — Bukkit flips it before invoking {@code onDisable()} — so scheduling a new task
     * via {@code runTask} would throw {@code IllegalPluginAccessException: "Plugin attempted
     * to register task while disabled"}. We detect that case (or simply being on the primary
     * thread already, which is always true during shutdown) and call the event directly
     * instead, since no thread-hop is needed or even possible at that point.
     */
    private void fireSaveEvent(UUID uuid, SwagPlayerProfile profile) {
        SwagPlayerDataSaveEvent event = new SwagPlayerDataSaveEvent(uuid, profile);
        if (!plugin.isEnabled() || Bukkit.isPrimaryThread()) {
            plugin.getServer().getPluginManager().callEvent(event);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getServer().getPluginManager().callEvent(event));
        }
    }

    private void upsertPlayer(SwagPlayerProfile profile) throws Exception {
        String sql;
        if (db.isMySQL()) {
            sql = "INSERT INTO swagapi_players (uuid, username, first_join, last_seen) VALUES (?,?,?,?)" +
                  " ON DUPLICATE KEY UPDATE username=VALUES(username), last_seen=VALUES(last_seen)";
        } else {
            sql = "INSERT OR REPLACE INTO swagapi_players (uuid, username, first_join, last_seen) VALUES (?,?,?,?)";
        }
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, profile.getUuid().toString());
            ps.setString(2, profile.getUsername());
            ps.setLong(3, profile.getFirstJoin());
            ps.setLong(4, profile.getLastSeen());
            ps.executeUpdate();
        }
    }

    /**
     * Flushes every cached profile synchronously on the calling thread. Intended to be
     * called from {@code SwagAPI#onDisable()} — at that point the Bukkit scheduler will
     * reject new task registrations (see {@link #fireSaveEvent}), so every DB write here
     * happens directly (blocking is expected and correct: the server is stopping) and no
     * work is handed off to the scheduler. Logs a single summary line rather than one line
     * per profile; individual failures still get their own warning from
     * {@link #saveProfileInternal} so they remain diagnosable.
     */
    @Override
    public void saveAll() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        int saved = 0;
        int failed = 0;
        for (UUID uuid : profileCache.keySet()) {
            if (saveProfileInternal(uuid)) {
                saved++;
            } else {
                failed++;
            }
        }
        profileCache.clear();
        plugin.getLogger().info("[SwagAPI] Saved " + saved + " player profile(s) on shutdown"
                + (failed > 0 ? " (" + failed + " failed — see warnings above)." : "."));
    }

    @Override
    public SwagPlayerProfile getProfile(UUID uuid) {
        return profileCache.get(uuid);
    }

    @Override
    public SwagPlayerProfile getProfile(Player player) {
        return getProfile(player.getUniqueId());
    }

    @Override
    public boolean isLoaded(UUID uuid) {
        return profileCache.containsKey(uuid);
    }

    @Override
    public void registerModule(String pluginKey, PlayerDataModule module) {
        modules.put(pluginKey, module);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getModuleData(UUID uuid, String pluginKey, Class<T> type) {
        SwagPlayerProfile profile = profileCache.get(uuid);
        if (profile == null) return null;
        Object data = profile.getModuleData(pluginKey);
        if (data == null) return null;
        if (!type.isInstance(data)) return null;
        return (T) data;
    }

    @Override
    public void setModuleData(UUID uuid, String pluginKey, Object data) {
        SwagPlayerProfile profile = profileCache.get(uuid);
        if (profile != null) {
            profile.setModuleData(pluginKey, data);
        }
    }

    public int getCachedProfileCount() {
        return profileCache.size();
    }
}
