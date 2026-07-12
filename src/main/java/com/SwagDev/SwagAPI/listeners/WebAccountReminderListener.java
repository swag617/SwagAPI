package com.SwagDev.SwagAPI.listeners;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.services.WebService;
import com.SwagDev.SwagAPI.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class WebAccountReminderListener implements Listener {

    private final SwagAPI plugin;

    public WebAccountReminderListener(SwagAPI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        WebService ws = plugin.getWebService();
        if (ws == null || !ws.isRunning() || !ws.isAuthEnabled()) return;

        Player player = event.getPlayer();

        // Deliver a queued password reset (requested while this player was offline), if any.
        // Not gated by the reminder toggle/permission below — a reset was explicitly requested
        // for this account, independent of whether they're currently OP or already have one.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) ws.deliverPendingResetIfAny(player);
        }, 20L);

        if (!plugin.getConfig().getBoolean("web-server.auth.remind-ops-on-join", true)) return;
        if (!player.hasPermission("swagapi.web")) return;
        if (ws.hasAccountLinkedTo(player.getUniqueId())) return;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            String url = ws.createAccountSetupUrl(player.getUniqueId());

            Component link = Component.text("[Click here to create one]", NamedTextColor.AQUA, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Opens in your browser", NamedTextColor.GRAY)));

            Component msg = ColorUtil.toComponent("&8[&bSwagAPI&8] &7No web panel account found. ")
                    .append(link);

            player.sendMessage(msg);
        }, 20L);
    }
}
