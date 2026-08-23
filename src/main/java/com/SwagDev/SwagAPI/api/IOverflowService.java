package com.SwagDev.SwagAPI.api;

import com.SwagDev.SwagAPI.model.OverflowEntry;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Ecosystem-wide "overflow inbox": a shared place any plugin can push an {@link ItemStack} into
 * when a player's inventory is full, instead of falling back to {@code
 * world.dropItemNaturally(...)} (a real drop-on-the-ground item that can be lost, griefed, or
 * simply missed). The player retrieves everything later via SwagCore's {@code /overflow} GUI.
 *
 * <p>Obtain via Bukkit's ServicesManager, the same pattern used for every other cross-plugin
 * service in this ecosystem:</p>
 * <pre>
 *     var reg = Bukkit.getServicesManager().getRegistration(IOverflowService.class);
 *     IOverflowService overflow = reg != null ? reg.getProvider() : null;
 * </pre>
 *
 * <p>This is a core SwagAPI service — registered unconditionally in {@code onEnable}, just like
 * {@link IDatabaseService} and {@link IPlayerDataService} — so it is always present whenever
 * SwagAPI itself is enabled. Callers still null-check the {@code ServicesManager} registration
 * before using it, the same defensive habit used everywhere else in this ecosystem for a plugin
 * that soft-depends on SwagAPI rather than hard-depending on it.</p>
 */
public interface IOverflowService {

    /**
     * Stores {@code item} for later retrieval by {@code playerUuid}. Persists asynchronously
     * (never blocks the calling thread) — call this directly from a synchronous event handler
     * exactly where you would otherwise have called {@code world.dropItemNaturally(...)}.
     *
     * @param playerUuid   the player who will receive this item back via {@code /overflow}.
     * @param sourcePlugin this plugin's own name (e.g. {@code "SwagFishing"}), shown to the
     *                     player so they know where the item came from. A blank/null value is
     *                     stored as {@code "Unknown"}.
     * @param item         the item to store. Ignored (no-op) if null or {@link
     *                     org.bukkit.Material#AIR}.
     */
    void storeOverflow(UUID playerUuid, String sourcePlugin, ItemStack item);

    /** Every item currently waiting for {@code playerUuid}, oldest first. */
    CompletableFuture<List<OverflowEntry>> getOverflowItems(UUID playerUuid);

    /** Removes one entry (by {@link OverflowEntry#getId()}) after it has been successfully
     *  delivered back into the player's real inventory. */
    CompletableFuture<Void> removeOverflowItem(UUID playerUuid, String entryId);

    /** Cheap count-only query — used for "you have N items waiting" join notifications without
     *  paying the cost of deserializing every stored ItemStack. */
    CompletableFuture<Integer> getOverflowCount(UUID playerUuid);
}
