package com.SwagDev.SwagAPI.api;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Fixes a real gap in Bukkit's {@code JavaPlugin#saveDefaultConfig()}: that call only writes
 * the bundled {@code config.yml} to disk the very first time a plugin starts (when the file
 * doesn't exist yet). On every later boot — including after a jar update that adds brand-new
 * config sections — it is a no-op, so an existing install silently never receives new keys the
 * developer added to the bundled default. This has already caused confirmed problems in this
 * ecosystem (e.g. SwagJobs shipping a new {@code builder.decay.*} block and buffed XP values
 * that never reached production because the on-disk {@code config.yml} already existed).
 *
 * <p>{@link #migrate(JavaPlugin)} recursively walks the plugin's bundled default YAML and adds
 * any key missing from the on-disk file, at any nesting depth — while never touching a key the
 * on-disk file already has, even if its value differs from the bundled default (an admin's
 * customization is never overwritten). Only maps/sections are merged recursively; a list value
 * is always treated as a single atomic value, never merged element-by-element.</p>
 *
 * <p><b>Synchronous by design.</b> This is plain file I/O, not a database call — there is no
 * {@code queryAsync}-style hang risk to design around, and a dependent plugin's {@code onEnable}
 * needs its fully-migrated config available the instant this call returns, so this must never
 * be made async.</p>
 *
 * <p><b>Known limitation — comments are not preserved on a migrated file.</b> This is backed by
 * Bukkit's {@link org.bukkit.configuration.file.YamlConfiguration}, which strips all comments
 * when it parses YAML (SnakeYAML gives it no comment nodes to keep) and never writes any back
 * on save. A file that needs no new keys is left completely untouched (see below), so its
 * comments survive — but the moment a file genuinely gets new keys added, the resulting save
 * will have lost any comments it had. There is no way to avoid this short of swapping the
 * backing YAML library ecosystem-wide, which is out of scope here.</p>
 *
 * <p>Nothing is written back to disk unless at least one key was actually added — a call that
 * finds nothing missing never touches the file's mtime.</p>
 */
public interface IConfigMigrationService {

    /**
     * Migrates {@code plugin}'s default {@code config.yml} (the bundled jar resource at
     * {@code config.yml}, merged into {@code <dataFolder>/config.yml}). Equivalent to
     * {@code migrate(plugin, "config.yml")}.
     *
     * <p>If the on-disk file doesn't exist at all yet, this behaves exactly like
     * {@code saveDefaultConfig()} (a plain first-time copy of the bundled resource) and returns
     * an empty list — there is nothing to "migrate" on a fresh install.</p>
     *
     * @param plugin the dependent plugin to migrate — supplies both the bundled resource (its
     *               jar) and the on-disk target (its data folder).
     * @return the dot-notation path of every key that was added (e.g.
     *         {@code "builder.decay.enabled"}), in the order encountered; empty if nothing
     *         needed to change (and, in that case, the file was not re-written).
     */
    List<String> migrate(JavaPlugin plugin);

    /**
     * Same as {@link #migrate(JavaPlugin)} but for a config file that isn't named
     * {@code config.yml} — {@code fileName} names both the bundled resource path inside the
     * plugin's jar and the file's path relative to the plugin's data folder (the same
     * convention {@code JavaPlugin#saveResource} already uses), e.g. {@code "menus.yml"}.
     *
     * @param plugin   the dependent plugin to migrate.
     * @param fileName resource path in the jar / relative path under the data folder, e.g.
     *                 {@code "menus.yml"}.
     * @return the dot-notation path of every key that was added; empty if nothing changed.
     */
    List<String> migrate(JavaPlugin plugin, String fileName);
}
