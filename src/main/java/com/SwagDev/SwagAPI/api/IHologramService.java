package com.SwagDev.SwagAPI.api;

import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;

import java.util.List;

/**
 * Cross-plugin access to SwagCore's native {@link TextDisplay}-based holograms, for a caller
 * that needs a text label spawned and rendered exactly like an admin hologram but does NOT want
 * it treated as one — i.e. it must never show up in {@code /hologram list}, never be persisted
 * across restarts, and never be reachable by delete-by-name lookups. Typical use: a plugin that
 * auto-generates many small, ephemeral labels (e.g. one per player-built structure) that are
 * conceptually a different category from the curated admin-authored hologram list.
 *
 * <p>Obtain via Bukkit's ServicesManager, the same pattern used for every other cross-plugin
 * service in this ecosystem:</p>
 * <pre>
 *     var reg = Bukkit.getServicesManager().getRegistration(IHologramService.class);
 *     IHologramService holograms = reg != null ? reg.getProvider() : null;
 * </pre>
 *
 * <p>This service is only registered once SwagCore's HologramsModule is enabled (unlike the core
 * services SwagAPI itself always registers) — a plugin that soft-depends on SwagCore for this
 * should null-check the registration before using it, same as every other optional integration
 * in this ecosystem.</p>
 */
public interface IHologramService {

    /**
     * Spawns a bare {@link TextDisplay} at {@code loc}, rendered with the exact same
     * placeholder/colour handling as a tracked admin hologram, but never added to SwagCore's own
     * name registry and never persisted to the database — invisible to {@code /hologram list},
     * {@code exists(name)}, and delete-by-name lookups.
     *
     * <p>The caller owns the returned entity's entire lifecycle from this point on: nothing in
     * SwagCore will ever move, refresh placeholders on, or remove it automatically. Hold onto the
     * returned reference and call {@code TextDisplay#remove()} yourself once it's no longer
     * needed (e.g. when the structure it labels is removed).</p>
     *
     * @param loc   where to spawn the label.
     * @param lines one or more lines of text (MiniMessage/legacy — same formatting a tracked
     *              hologram's lines accept), joined with newlines.
     */
    TextDisplay spawnUntracked(Location loc, List<String> lines);
}
