package com.SwagDev.SwagAPI.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Map;

public interface IMessagingService {

    void send(Player player, String message);

    void send(Player player, String message, Map<String, String> placeholders);

    void broadcast(String message);

    void broadcastPermission(String message, String permission);

    void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut);

    void sendActionBar(Player player, String message);

    String colorize(String input);

    Component toComponent(String input);
}
