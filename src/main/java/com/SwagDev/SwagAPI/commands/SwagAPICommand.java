package com.SwagDev.SwagAPI.commands;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.model.PluginUpdateInfo;
import com.SwagDev.SwagAPI.services.UpdateService;
import com.SwagDev.SwagAPI.services.WebService;
import com.SwagDev.SwagAPI.util.ColorUtil;
import com.zaxxer.hikari.HikariPoolMXBean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class SwagAPICommand implements CommandExecutor, TabCompleter {

    private final SwagAPI plugin;

    public SwagAPICommand(SwagAPI plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("swagapi.admin")) {
            sender.sendMessage(ColorUtil.colorize("&cYou do not have permission to use this command."));
            return true;
        }

        // /webeditor is a direct shortcut to /swagapi web — same handler, same subcommands
        // (setup/whoami/resetpassword), just without needing "web" as the first word every time.
        if (label.equalsIgnoreCase("webeditor")) {
            String[] shifted = new String[args.length + 1];
            shifted[0] = "web";
            System.arraycopy(args, 0, shifted, 1, args.length);
            handleWeb(sender, shifted);
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload"      -> handleReload(sender);
            case "status"      -> handleStatus(sender);
            case "info"        -> handleInfo(sender);
            case "updatecheck" -> handleUpdateCheck(sender);
            case "update"      -> handleUpdate(sender, args);
            case "updates"     -> handleUpdates(sender, args);
            case "web"         -> handleWeb(sender, args);
            default            -> sendUsage(sender);
        }
        return true;
    }

    // ─── Tab completion ─────────────────────────────────────────────────────

    private static final List<String> SUBCOMMANDS =
            List.of("reload", "status", "info", "updatecheck", "update", "updates", "web");
    private static final List<String> WEB_SUBCOMMANDS = List.of("setup", "whoami", "resetpassword");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("swagapi.admin")) return List.of();

        if (alias.equalsIgnoreCase("webeditor")) {
            if (args.length == 1) return filterPrefix(WEB_SUBCOMMANDS, args[0]);
            if (args.length == 2 && args[0].equalsIgnoreCase("whoami")) {
                return filterPrefix(onlinePlayerNames(), args[1]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("resetpassword")) {
                WebService ws = plugin.getWebService();
                return ws != null ? filterPrefix(ws.getAllAccountUsernames(), args[1]) : List.of();
            }
            return List.of();
        }

        if (args.length == 1) {
            return filterPrefix(SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            return switch (sub) {
                case "update"  -> filterPrefix(pendingUpdateNames(), args[1]);
                case "updates" -> filterPrefix(List.of("cancel"), args[1]);
                case "web"     -> filterPrefix(WEB_SUBCOMMANDS, args[1]);
                default        -> List.of();
            };
        }

        if (args.length == 3) {
            if (sub.equals("updates") && args[1].equalsIgnoreCase("cancel")) {
                UpdateService us = plugin.getUpdateService();
                return us != null ? filterPrefix(us.getStagedPlugins(), args[2]) : List.of();
            }
            if (sub.equals("web") && args[1].equalsIgnoreCase("whoami")) {
                return filterPrefix(onlinePlayerNames(), args[2]);
            }
            if (sub.equals("web") && args[1].equalsIgnoreCase("resetpassword")) {
                WebService ws = plugin.getWebService();
                return ws != null ? filterPrefix(ws.getAllAccountUsernames(), args[2]) : List.of();
            }
        }

        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }

    private List<String> pendingUpdateNames() {
        UpdateService us = plugin.getUpdateService();
        if (us == null) return List.of();
        List<String> names = new ArrayList<>();
        for (PluginUpdateInfo info : us.getCachedUpdates()) names.add(info.pluginName());
        return names;
    }

    private static List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    // ─── Existing handlers (unchanged) ───────────────────────────────────────

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getMessageUtil().reload();
        sender.sendMessage(ColorUtil.colorize("&a[SwagAPI] Configuration reloaded. (DB pool not restarted)"));
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage(ColorUtil.colorize("&8--- &bSwagAPI Status &8---"));
        String dbType = plugin.getConfig().getString("database.type", "sqlite");
        sender.sendMessage(ColorUtil.colorize("&7Database type: &f" + dbType));
        HikariPoolMXBean poolBean = plugin.getDatabaseService().getDataSource().getHikariPoolMXBean();
        if (poolBean != null) {
            sender.sendMessage(ColorUtil.colorize("&7Pool - Active: &f" + poolBean.getActiveConnections()
                    + " &7Idle: &f" + poolBean.getIdleConnections()
                    + " &7Total: &f" + poolBean.getTotalConnections()));
        }
        sender.sendMessage(ColorUtil.colorize("&7Vault/Economy: &f"
                + (plugin.getEconomyService().isEnabled() ? "&aEnabled" : "&cDisabled")));
        sender.sendMessage(ColorUtil.colorize("&7Cached profiles: &f"
                + plugin.getPlayerDataService().getCachedProfileCount()));
        sender.sendMessage(ColorUtil.colorize("&7Event bus subscriptions: &f"
                + plugin.getEventBusService().getTotalSubscriptionCount()));

        // Update system status
        UpdateService us = plugin.getUpdateService();
        if (us != null) {
            sender.sendMessage(ColorUtil.colorize("&7Update System: "
                    + (us.isEnabled() ? "&aEnabled" : "&cDisabled")));
            if (us.isEnabled()) {
                sender.sendMessage(ColorUtil.colorize("&7Pending Updates: &e"
                        + us.getCachedUpdates().size()));
                sender.sendMessage(ColorUtil.colorize("&7Staged for Reboot: &e"
                        + us.getStagedPlugins().size()));
            }
        }

        // Web server status
        WebService ws = plugin.getWebService();
        if (ws != null) {
            sender.sendMessage(ColorUtil.colorize("&7Web Server: "
                    + (ws.isRunning() ? "&aRunning (port " + ws.getPort() + ")" : "&cStopped")));
            if (ws.isRunning()) {
                sender.sendMessage(ColorUtil.colorize("&7Web Modules: &e"
                        + ws.getRegisteredModules().size()));
            }
        }
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage(ColorUtil.colorize("&8--- &bSwagAPI Info &8---"));
        sender.sendMessage(ColorUtil.colorize("&7Version: &f" + plugin.getPluginMeta().getVersion()));
        sender.sendMessage(ColorUtil.colorize("&7Paper: &f" + Bukkit.getVersion()));
        sender.sendMessage(ColorUtil.colorize("&7Services registered:"));
        sender.sendMessage(ColorUtil.colorize("  &8- &7IDatabaseService, IEconomyService, IPlayerDataService"));
        sender.sendMessage(ColorUtil.colorize("  &8- &7IMessagingService, IEventBusService, IUpdateService, IWebService"));
        sender.sendMessage(ColorUtil.colorize("&7Dependent plugins using SwagAPI:"));
        for (Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
            if (loaded.equals(plugin)) continue;
            if (loaded.getDescription().getDepend().contains("SwagAPI")
                    || loaded.getDescription().getSoftDepend().contains("SwagAPI")) {
                sender.sendMessage(ColorUtil.colorize("  &8- &f" + loaded.getName()
                        + " &7v" + loaded.getPluginMeta().getVersion()));
            }
        }
    }

    // ─── Update handlers ──────────────────────────────────────────────────────

    /**
     * /swagapi updatecheck
     * Triggers an async manifest fetch and reports the result to the sender.
     */
    private void handleUpdateCheck(CommandSender sender) {
        UpdateService us = plugin.getUpdateService();
        if (us == null || !us.isEnabled()) {
            sender.sendMessage(ColorUtil.colorize("&cUpdate system is not enabled."));
            return;
        }
        sender.sendMessage(ColorUtil.colorize("&7Checking for updates..."));
        us.checkForUpdates().thenAccept(updates -> {
            // checkForUpdates() completes on a BukkitAsync thread; dispatch messages back to main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (updates.isEmpty()) {
                    sender.sendMessage(ColorUtil.colorize("&aAll plugins are up to date!"));
                } else {
                    sender.sendMessage(ColorUtil.colorize("&e" + updates.size() + " update(s) available:"));
                    for (PluginUpdateInfo info : updates) {
                        sender.sendMessage(ColorUtil.colorize("  &8- &b" + info.pluginName()
                                + " &7(" + info.currentVersion()
                                + " &8-> &a" + info.latestVersion() + "&7)"));
                    }
                }
            });
        });
    }

    /**
     * /swagapi update <plugin> [--confirm]
     *
     * Without --confirm: shows a clickable confirmation prompt (players) or auto-proceeds (console).
     * With --confirm: immediately stages the update via async download + checksum validation.
     */
    private void handleUpdate(CommandSender sender, String[] args) {
        UpdateService us = plugin.getUpdateService();
        if (us == null || !us.isEnabled()) {
            sender.sendMessage(ColorUtil.colorize("&cUpdate system is not enabled."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.colorize("&cUsage: /swagapi update <plugin> [--confirm]"));
            return;
        }

        String pluginName = args[1];
        Optional<PluginUpdateInfo> infoOpt = us.getUpdateInfo(pluginName);
        if (infoOpt.isEmpty()) {
            sender.sendMessage(ColorUtil.colorize(
                    "&cNo pending update found for: &f" + pluginName
                    + "&c. Run /swagapi updatecheck first."));
            return;
        }
        PluginUpdateInfo info = infoOpt.get();

        boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("--confirm");

        if (!confirmed && sender instanceof Player) {
            // Show clickable confirmation prompt for in-game players
            Player player = (Player) sender;

            Component confirmBtn = Component.text("[Confirm]", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(
                            "/swagapi update " + pluginName + " --confirm"))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Stage update for " + pluginName, NamedTextColor.GRAY)));

            Component cancelBtn = Component.text("[Cancel]", NamedTextColor.RED, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(
                            "/swagapi updates cancel " + pluginName))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Cancel this update", NamedTextColor.GRAY)));

            Component prompt = ColorUtil.toComponent(
                    "&7Stage &b" + info.pluginName()
                    + " &7v&a" + info.latestVersion()
                    + " &7(current: &c" + info.currentVersion() + "&7)? ")
                    .append(confirmBtn)
                    .append(Component.text("  "))
                    .append(cancelBtn);

            player.sendMessage(prompt);
            return;
        }

        // Console path or player who already clicked --confirm: proceed to staging
        sender.sendMessage(ColorUtil.colorize(
                "&7Staging &b" + pluginName + " &7v&a" + info.latestVersion() + "&7..."));

        us.stageUpdate(pluginName).thenAccept(success -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (success) {
                    sender.sendMessage(ColorUtil.colorize(
                            "&a" + pluginName + " staged successfully."
                            + " Restart the server to apply the update."));
                } else {
                    sender.sendMessage(ColorUtil.colorize(
                            "&cFailed to stage update for " + pluginName
                            + ". Check console for details."));
                }
            });
        });
    }

    /**
     * /swagapi updates [cancel <plugin>]
     *
     * Without arguments: lists all plugins staged for the next restart.
     * With 'cancel <plugin>': removes the staged jar and cancels the pending update.
     */
    private void handleUpdates(CommandSender sender, String[] args) {
        UpdateService us = plugin.getUpdateService();
        if (us == null || !us.isEnabled()) {
            sender.sendMessage(ColorUtil.colorize("&cUpdate system is not enabled."));
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            if (args.length < 3) {
                sender.sendMessage(ColorUtil.colorize("&cUsage: /swagapi updates cancel <plugin>"));
                return;
            }
            String pluginName = args[2];
            boolean cancelled = us.cancelStagedUpdate(pluginName);
            if (cancelled) {
                sender.sendMessage(ColorUtil.colorize(
                        "&aCancelled staged update for &b" + pluginName + "&a."));
            } else {
                sender.sendMessage(ColorUtil.colorize(
                        "&cNo staged update found for: &f" + pluginName));
            }
            return;
        }

        // List staged plugins
        List<String> staged = us.getStagedPlugins();
        if (staged.isEmpty()) {
            sender.sendMessage(ColorUtil.colorize("&7No updates staged for next restart."));
        } else {
            sender.sendMessage(ColorUtil.colorize("&7Staged for next restart (" + staged.size() + "):"));
            for (String name : staged) {
                sender.sendMessage(ColorUtil.colorize("  &8- &b" + name));
            }
        }
    }

    // ─── Web handler ─────────────────────────────────────────────────────────

    /**
     * /swagapi web [setup|whoami|resetpassword]
     * Displays the HTTP web server status and lists all registered module URLs.
     * With 'setup': sends the sender a fresh clickable link to create their web panel account.
     * With 'whoami [player]': shows which panel account(s) are linked to a player.
     * With 'resetpassword <username>': sends that account a password reset link.
     */
    private void handleWeb(CommandSender sender, String[] args) {
        WebService ws = plugin.getWebService();
        if (ws == null || !ws.isRunning()) {
            sender.sendMessage(ColorUtil.colorize("&c[SwagAPI] Web server is not running."));
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("setup")) {
            handleWebSetup(sender, ws);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("whoami")) {
            handleWebWhoami(sender, args, ws);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("resetpassword")) {
            handleWebResetPassword(sender, args, ws);
            return;
        }

        sender.sendMessage(ColorUtil.colorize("&8--- &bSwagAPI Web Server &8---"));
        sender.sendMessage(ColorUtil.colorize("&7Port: &f" + ws.getPort()));
        sender.sendMessage(ColorUtil.colorize("&7Bind: &f" + ws.getBindAddress()));

        sender.sendMessage(ColorUtil.toComponent("&7Home dashboard: &b").append(openLink(ws.getHomeUrl())));

        sender.sendMessage(ColorUtil.colorize("&7Registered modules &8(&f"
                + ws.getRegisteredModules().size() + "&8):"));
        for (String module : ws.getRegisteredModules()) {
            String url = ws.getPluginUrl(module);
            sender.sendMessage(ColorUtil.toComponent("  &8- &b" + capitalize(module) + " &7")
                    .append(openLink(url)));
        }
    }

    /** A clickable "[Open]" button that opens the given URL in the sender's browser, with the URL shown on hover. */
    private static Component openLink(String url) {
        return Component.text("[Open]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(url, NamedTextColor.GRAY)));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void handleWebSetup(CommandSender sender, WebService ws) {
        if (!ws.isAuthEnabled()) {
            sender.sendMessage(ColorUtil.colorize("&7Web panel auth is disabled — no login required."));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ColorUtil.colorize("&cThis command must be run by a player."));
            return;
        }
        String url = ws.createAccountSetupUrl(player.getUniqueId());
        Component link = Component.text("[Click here to create your account]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Opens in your browser — link expires soon", NamedTextColor.GRAY)));
        player.sendMessage(ColorUtil.toComponent("&8[&bSwagAPI&8] &7").append(link));
    }

    private void handleWebWhoami(CommandSender sender, String[] args, WebService ws) {
        Player target;
        if (args.length >= 3) {
            target = plugin.getServer().getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(ColorUtil.colorize("&cPlayer not found or offline: " + args[2]));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(ColorUtil.colorize("&cConsole must specify a player: /swagapi web whoami <player>"));
            return;
        }

        List<String> usernames = ws.getAccountUsernamesLinkedTo(target.getUniqueId());
        String who = target.equals(sender) ? "you" : target.getName();
        if (usernames.isEmpty()) {
            sender.sendMessage(ColorUtil.colorize("&7No web panel account is linked to " + who + "."));
            return;
        }
        sender.sendMessage(ColorUtil.colorize("&7Web panel account(s) for " + who + ": &b"
                + String.join("&7, &b", usernames)));
    }

    private void handleWebResetPassword(CommandSender sender, String[] args, WebService ws) {
        if (args.length < 3) {
            sender.sendMessage(ColorUtil.colorize("&cUsage: /swagapi web resetpassword <username>"));
            return;
        }
        String username = args[2];
        WebService.ResetOutcome outcome = ws.initiatePasswordReset(username);
        switch (outcome) {
            case DELIVERED -> sender.sendMessage(ColorUtil.colorize(
                    "&aSent a password reset link to " + username + " in-game."));
            case QUEUED -> sender.sendMessage(ColorUtil.colorize(
                    "&e" + username + "'s linked player is offline — the reset link will be delivered next time they join."));
            case NO_LINKED_PLAYER -> sender.sendMessage(ColorUtil.colorize(
                    "&cThat account has no linked player to deliver a link to."));
            case NOT_FOUND -> sender.sendMessage(ColorUtil.colorize(
                    "&cNo web panel account named '" + username + "'."));
        }
    }

    // ─── Usage ───────────────────────────────────────────────────────────────

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ColorUtil.colorize(
                "&cUsage: /swagapi <reload|status|info|updatecheck|update|updates|web>"));
    }
}
