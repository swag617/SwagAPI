package com.SwagDev.SwagAPI.api;

import com.SwagDev.SwagAPI.events.SwagCrossPluginMessageEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

public interface IEventBusService {

    void publish(SwagCrossPluginMessageEvent event);

    void subscribe(String channel, Consumer<SwagCrossPluginMessageEvent> handler, Plugin owner);

    void unsubscribeAll(Plugin owner);
}
