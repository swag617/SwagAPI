package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IConfigMigrationService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Backs {@link IConfigMigrationService}. See that interface for the full rationale and the
 * documented comment-preservation limitation. This class holds no state of its own — every
 * call operates entirely on the {@link JavaPlugin} instance passed in, not on SwagAPI's own
 * data — the {@link SwagAPI} reference is kept only for consistency with every other service
 * here and for SwagAPI-side diagnostic logging (a dependent plugin's own migration summary is
 * logged through ITS OWN logger, not SwagAPI's, so it shows up under that plugin's name).
 */
public final class ConfigMigrationService implements IConfigMigrationService {

    private final SwagAPI plugin;

    public ConfigMigrationService(SwagAPI plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> migrate(JavaPlugin target) {
        return migrate(target, "config.yml");
    }

    @Override
    public List<String> migrate(JavaPlugin target, String fileName) {
        if (target == null || fileName == null || fileName.isBlank()) {
            return Collections.emptyList();
        }

        File dataFolder = target.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File onDiskFile = new File(dataFolder, fileName);

        if (!onDiskFile.exists()) {
            // First-time install — plain copy of the bundled default, same as saveDefaultConfig().
            // Nothing was "migrated" since there's nothing on disk yet to diff against.
            target.saveResource(fileName, false);
            return Collections.emptyList();
        }

        InputStream resourceStream = target.getResource(fileName);
        if (resourceStream == null) {
            target.getLogger().warning("[ConfigMigration] No bundled resource '" + fileName
                    + "' found in this plugin's jar — skipping config migration.");
            return Collections.emptyList();
        }

        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(resourceStream, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            target.getLogger().log(Level.WARNING,
                    "[ConfigMigration] Failed to read bundled resource '" + fileName + "'", e);
            return Collections.emptyList();
        }

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(onDiskFile);

        List<String> added = mergeInto(defaults, onDisk, "");
        if (added.isEmpty()) {
            // Nothing missing — deliberately do NOT re-save, so the file's mtime (and any
            // comments it still has) are left completely untouched.
            return added;
        }

        try {
            onDisk.save(onDiskFile);
            target.getLogger().info("[ConfigMigration] " + target.getName() + ": added "
                    + added.size() + " new config key(s) to '" + fileName + "': "
                    + String.join(", ", added));
        } catch (IOException e) {
            target.getLogger().log(Level.WARNING,
                    "[ConfigMigration] Failed to save migrated '" + fileName + "'", e);
        }

        return added;
    }

    /**
     * Recursively adds every key present in {@code defaultSection} but missing from
     * {@code targetSection} (a section at the same nesting depth in the on-disk config),
     * returning the dot-notation paths (rooted at {@code pathPrefix}) of everything added.
     *
     * <p>A key whose default value is itself a {@link ConfigurationSection} is descended into;
     * every other value type — including {@link java.util.List} — is treated as a single
     * atomic leaf: copied over whole if missing, left completely alone if already present.</p>
     *
     * <p>If the on-disk file has a non-section value sitting at a path where the default has a
     * whole section, that on-disk value is left exactly as-is and not descended into — an
     * admin's customization always wins, even across a type mismatch like this.</p>
     */
    private List<String> mergeInto(ConfigurationSection defaultSection, ConfigurationSection targetSection,
                                    String pathPrefix) {
        List<String> added = new ArrayList<>();
        for (String key : defaultSection.getKeys(false)) {
            String fullPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            Object defaultValue = defaultSection.get(key);

            if (defaultValue instanceof ConfigurationSection defaultSub) {
                ConfigurationSection targetSub = targetSection.isConfigurationSection(key)
                        ? targetSection.getConfigurationSection(key)
                        : null;
                if (targetSub == null) {
                    if (targetSection.isSet(key)) {
                        // On-disk has some non-section scalar/list sitting at this path —
                        // leave it alone entirely rather than guess which side "wins".
                        continue;
                    }
                    targetSub = targetSection.createSection(key);
                }
                added.addAll(mergeInto(defaultSub, targetSub, fullPath));
            } else {
                if (!targetSection.isSet(key)) {
                    targetSection.set(key, defaultValue);
                    added.add(fullPath);
                }
            }
        }
        return added;
    }
}
