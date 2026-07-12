package com.SwagDev.SwagAPI.listeners;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.model.PluginUpdateInfo;
import com.SwagDev.SwagAPI.services.UpdateService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class AdminJoinUpdateListener implements Listener {

    private final SwagAPI plugin;

    public AdminJoinUpdateListener(SwagAPI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("updates.notify-on-join", true)) return;

        UpdateService us = plugin.getUpdateService();
        if (us == null || !us.isEnabled()) return;
        if (!event.getPlayer().hasPermission("swagapi.admin")) return;
        if (us.getCachedUpdates().isEmpty()) return;

        // Delay 20 ticks (1 s) so the player is fully connected before receiving chat messages
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (PluginUpdateInfo info : us.getCachedUpdates()) {
                if (!us.isStaged(info.pluginName())) {
                    us.sendUpdateNotification(event.getPlayer(), info);
                }
            }
        }, 20L);
    }
}
