package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IPrefixService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Backs {@link IPrefixService} with a small dedicated {@code prefixes.yml} — deliberately NOT
 * the shared SQLite database, since this is a handful of admin-authored strings edited only
 * through the web panel's {@code /prefixes} page, not gameplay data. There is exactly one
 * writer (that same page, in this same JVM), so every write updates the in-memory cache
 * directly — no reload mechanism needed, unlike data another process/server might also touch.
 */
public final class PrefixService implements IPrefixService {

    private static final String GLOBAL_KEY = "*";

    private final SwagAPI plugin;
    private final File file;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PrefixService(SwagAPI plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "prefixes.yml");
    }

    public void initialize() {
        load();
    }

    private void load() {
        cache.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String global = yaml.getString("global");
        if (global != null && !global.isBlank()) cache.put(GLOBAL_KEY, global);
        ConfigurationSection section = yaml.getConfigurationSection("overrides");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null && !value.isBlank()) cache.put(key, value);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        String global = cache.get(GLOBAL_KEY);
        if (global != null) yaml.set("global", global);
        for (Map.Entry<String, String> entry : cache.entrySet()) {
            if (entry.getKey().equals(GLOBAL_KEY)) continue;
            yaml.set("overrides." + entry.getKey(), entry.getValue());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[SwagAPI] Failed to save prefixes.yml", e);
        }
    }

    @Override
    public String getPrefix(String pluginName, String fallback) {
        if (cache.containsKey(pluginName)) return cache.get(pluginName);
        if (cache.containsKey(GLOBAL_KEY)) return cache.get(GLOBAL_KEY);
        return fallback;
    }

    /** Every currently-set override, including the global one under {@link #globalKey()} —
     *  used by the web panel to pre-fill the form on load. */
    public Map<String, String> getAllOverrides() {
        return new LinkedHashMap<>(cache);
    }

    /** A blank/null {@code value} clears that override (falls through to the next link in the
     *  chain again); pass {@link #globalKey()} as {@code pluginName} to set/clear the global one. */
    public void setOverride(String pluginName, String value) {
        if (value == null || value.isBlank()) {
            cache.remove(pluginName);
        } else {
            cache.put(pluginName, value);
        }
        save();
    }

    public static String globalKey() { return GLOBAL_KEY; }
}
