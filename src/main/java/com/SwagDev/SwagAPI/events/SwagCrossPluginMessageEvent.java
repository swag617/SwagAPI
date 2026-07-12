package com.SwagDev.SwagAPI.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public class SwagCrossPluginMessageEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String channel;
    private final String sourcePlugin;
    private final Map<String, Object> data;
    private final UUID playerUuid;

    public SwagCrossPluginMessageEvent(String channel, String sourcePlugin, Map<String, Object> data, UUID playerUuid) {
        this.channel = channel;
        this.sourcePlugin = sourcePlugin;
        this.data = Collections.unmodifiableMap(data);
        this.playerUuid = playerUuid;
    }

    public String getChannel() {
        return channel;
    }

    public String getSourcePlugin() {
        return sourcePlugin;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
