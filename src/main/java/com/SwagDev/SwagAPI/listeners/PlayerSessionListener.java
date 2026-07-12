package com.SwagDev.SwagAPI.listeners;

import com.SwagDev.SwagAPI.SwagAPI;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerSessionListener implements Listener {

    private final SwagAPI plugin;

    public PlayerSessionListener(SwagAPI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getPlayerDataService().loadProfile(uuid).exceptionally(ex -> {
            plugin.getLogger().warning("Could not load profile for " + uuid + ": " + ex.getMessage());
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getPlayerDataService().saveProfile(uuid).thenRun(() ->
                plugin.getLogger().fine("Profile saved for " + uuid));
    }
}
