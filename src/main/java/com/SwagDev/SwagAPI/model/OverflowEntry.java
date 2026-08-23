package com.SwagDev.SwagAPI.model;

import org.bukkit.inventory.ItemStack;

/**
 * A single item waiting in a player's ecosystem-wide overflow inbox (see
 * {@link com.SwagDev.SwagAPI.api.IOverflowService}). Immutable — {@link #getItem()} returns a
 * defensive clone so a caller can never mutate the stored stack without going through the
 * service.
 */
public final class OverflowEntry {

    private final String id;
    private final String sourcePlugin;
    private final long storedAt;
    private final ItemStack item;

    public OverflowEntry(String id, String sourcePlugin, long storedAt, ItemStack item) {
        this.id = id;
        this.sourcePlugin = sourcePlugin;
        this.storedAt = storedAt;
        this.item = item;
    }

    /** Unique id of this entry (a random UUID string), used to remove it after withdrawal. */
    public String getId() { return id; }

    /** Name of the plugin that stored this item (e.g. {@code "SwagFishing"}), for display only. */
    public String getSourcePlugin() { return sourcePlugin; }

    /** {@link System#currentTimeMillis()} at the time this item was stored. */
    public long getStoredAt() { return storedAt; }

    /** A defensive clone of the stored item — safe to hand to a GUI or an inventory. */
    public ItemStack getItem() { return item.clone(); }
}
