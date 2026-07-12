package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IMessagingService;
import com.SwagDev.SwagAPI.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;

public class MessagingService implements IMessagingService {

    private final SwagAPI plugin;

    public MessagingService(SwagAPI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void send(Player player, String message) {
        player.sendMessage(ColorUtil.colorize(message));
    }

    @Override
    public void send(Player player, String message, Map<String, String> placeholders) {
        String processed = message;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            processed = processed.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        send(player, processed);
    }

    @Override
    public void broadcast(String message) {
        String colorized = ColorUtil.colorize(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(colorized);
        }
    }

    @Override
    public void broadcastPermission(String message, String permission) {
        String colorized = ColorUtil.colorize(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(colorized);
            }
        }
    }

    @Override
    public void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.showTitle(Title.title(
                ColorUtil.toComponent(title),
                ColorUtil.toComponent(subtitle),
                Title.Times.times(
                        Duration.ofMillis(fadeIn * 50L),
                        Duration.ofMillis(stay * 50L),
                        Duration.ofMillis(fadeOut * 50L)
                )
        ));
    }

    @Override
    public void sendActionBar(Player player, String message) {
        player.sendActionBar(ColorUtil.toComponent(message));
    }

    @Override
    public String colorize(String input) {
        return ColorUtil.colorize(input);
    }

    @Override
    public Component toComponent(String input) {
        return ColorUtil.toComponent(input);
    }
}
