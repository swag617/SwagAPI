package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IOverflowService;
import com.SwagDev.SwagAPI.model.OverflowEntry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Backs {@link IOverflowService} with a dedicated {@code swagapi_overflow_items} table (created
 * by {@link DatabaseService}) in the shared database, rather than {@link PlayerDataService}'s
 * generic per-plugin JSON blob — overflow entries are added and removed individually, by
 * whichever plugin happens to call in, not owned by a single "module key" the way per-plugin
 * player data is.
 *
 * <p>ItemStacks round-trip through Bukkit's native object-stream serialization (Base64-encoded),
 * the same approach SwagFishing's Tackle Loadouts feature ({@code LoadoutManager}) uses for its
 * stored rod — preserves enchantments/lore/NBT-ish meta exactly, no custom item format to
 * maintain.</p>
 */
public class OverflowService implements IOverflowService {

    private final SwagAPI plugin;
    private final DatabaseService db;

    public OverflowService(SwagAPI plugin, DatabaseService db) {
        this.plugin = plugin;
        this.db = db;
    }

    @Override
    public void storeOverflow(UUID playerUuid, String sourcePlugin, ItemStack item) {
        if (playerUuid == null || item == null || item.getType() == Material.AIR) return;

        String data = itemStackToBase64(item);
        if (data == null) {
            plugin.getLogger().warning("[SwagAPI] Failed to serialize an overflow item for "
                    + playerUuid + " from " + sourcePlugin + " — item lost.");
            return;
        }

        String id = UUID.randomUUID().toString();
        String source = (sourcePlugin == null || sourcePlugin.isBlank()) ? "Unknown" : sourcePlugin;
        long now = System.currentTimeMillis();

        db.executeAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO swagapi_overflow_items (id, uuid, source_plugin, item_data, stored_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, source);
                ps.setString(4, data);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[SwagAPI] Failed to store overflow item for " + playerUuid, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<OverflowEntry>> getOverflowItems(UUID playerUuid) {
        return db.queryAsync(() -> {
            List<OverflowEntry> results = new ArrayList<>();
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id, source_plugin, item_data, stored_at FROM swagapi_overflow_items WHERE uuid = ? ORDER BY stored_at ASC")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ItemStack item = itemStackFromBase64(rs.getString("item_data"));
                        if (item == null) continue; // corrupt/unreadable entry — skip rather than crash the GUI
                        results.add(new OverflowEntry(
                                rs.getString("id"),
                                rs.getString("source_plugin"),
                                rs.getLong("stored_at"),
                                item));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[SwagAPI] Failed to load overflow items for " + playerUuid, e);
            }
            return results;
        });
    }

    @Override
    public CompletableFuture<Void> removeOverflowItem(UUID playerUuid, String entryId) {
        return db.queryAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM swagapi_overflow_items WHERE id = ? AND uuid = ?")) {
                ps.setString(1, entryId);
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[SwagAPI] Failed to remove overflow item " + entryId + " for " + playerUuid, e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> getOverflowCount(UUID playerUuid) {
        return db.queryAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT COUNT(*) AS cnt FROM swagapi_overflow_items WHERE uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("cnt") : 0;
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[SwagAPI] Failed to count overflow items for " + playerUuid, e);
                return 0;
            }
        });
    }

    // ── ItemStack <-> Base64 (standard Bukkit object-stream serialization; preserves NBT/meta) ──

    private static String itemStackToBase64(ItemStack item) {
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOut = new BukkitObjectOutputStream(byteOut)) {
            dataOut.writeObject(item);
            dataOut.flush();
            return Base64.getEncoder().encodeToString(byteOut.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    private static ItemStack itemStackFromBase64(String data) {
        if (data == null || data.isBlank()) return null;
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataIn = new BukkitObjectInputStream(byteIn)) {
            Object obj = dataIn.readObject();
            return (obj instanceof ItemStack stack) ? stack : null;
        } catch (IOException | ClassNotFoundException | IllegalArgumentException e) {
            return null;
        }
    }
}
