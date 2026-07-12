package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IEventBusService;
import com.SwagDev.SwagAPI.events.SwagCrossPluginMessageEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class EventBusService implements IEventBusService {

    private final SwagAPI plugin;
    private final Map<String, List<Map.Entry<Consumer<SwagCrossPluginMessageEvent>, Plugin>>> subscriptions =
            new ConcurrentHashMap<>();

    public EventBusService(SwagAPI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void publish(SwagCrossPluginMessageEvent event) {
        Bukkit.getPluginManager().callEvent(event);
        List<Map.Entry<Consumer<SwagCrossPluginMessageEvent>, Plugin>> handlers =
                subscriptions.get(event.getChannel());
        if (handlers != null) {
            for (Map.Entry<Consumer<SwagCrossPluginMessageEvent>, Plugin> entry : handlers) {
                try {
                    entry.getKey().accept(event);
                } catch (Exception e) {
                    plugin.getLogger().warning("EventBus handler threw exception on channel '"
                            + event.getChannel() + "' from plugin '"
                            + entry.getValue().getName() + "': " + e.getMessage());
                }
            }
        }
        if (plugin.getConfig().getBoolean("event-bus.log-events", false)) {
            plugin.getLogger().info("[EventBus] Published '" + event.getChannel()
                    + "' from '" + event.getSourcePlugin() + "'");
        }
    }

    @Override
    public void subscribe(String channel, Consumer<SwagCrossPluginMessageEvent> handler, Plugin owner) {
        subscriptions.computeIfAbsent(channel, k -> new ArrayList<>())
                .add(new AbstractMap.SimpleEntry<>(handler, owner));
    }

    @Override
    public void unsubscribeAll(Plugin owner) {
        subscriptions.values().forEach(list ->
                list.removeIf(entry -> entry.getValue().equals(owner)));
    }

    public int getTotalSubscriptionCount() {
        return subscriptions.values().stream().mapToInt(List::size).sum();
    }
}
