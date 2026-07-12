package com.SwagDev.SwagAPI.util;

import com.SwagDev.SwagAPI.SwagAPI;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Map;

public class MessageUtil {

    private YamlConfiguration messages;
    private final SwagAPI plugin;

    public MessageUtil(SwagAPI plugin) {
        this.plugin = plugin;
        reload();
    }

    public String get(String key) {
        String value = messages.getString(key);
        if (value == null) {
            plugin.getLogger().warning("Missing messages.yml key: " + key);
            return key;
        }
        return ColorUtil.colorize(value);
    }

    public String get(String key, Map<String, String> placeholders) {
        String value = get(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }

    public void send(Player player, String key) {
        player.sendMessage(get(key));
    }

    public void send(Player player, String key, Map<String, String> placeholders) {
        player.sendMessage(get(key, placeholders));
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }
}
