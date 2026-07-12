# Event Bus Service

`IEventBusService` is an in-process publish/subscribe channel for cross-plugin messaging. It lets two Swag617 plugins talk to each other by channel name, without either one taking a hard compile-time dependency on the other.

## Obtaining it

```java
RegisteredServiceProvider<IEventBusService> rsp =
        Bukkit.getServicesManager().getRegistration(IEventBusService.class);
IEventBusService eventBus = rsp.getProvider();
```

## Interface

```java
public interface IEventBusService {
    void publish(SwagCrossPluginMessageEvent event);
    void subscribe(String channel, Consumer<SwagCrossPluginMessageEvent> handler, Plugin owner);
    void unsubscribeAll(Plugin owner);
}
```

## `SwagCrossPluginMessageEvent`

```java
public class SwagCrossPluginMessageEvent extends Event {
    public SwagCrossPluginMessageEvent(String channel, String sourcePlugin,
                                        Map<String, Object> data, UUID playerUuid);

    String getChannel();
    String getSourcePlugin();
    Map<String, Object> getData();   // unmodifiable
    UUID getPlayerUuid();            // may be null for non-player-scoped messages
}
```

This is also a normal Bukkit `Event`, so anyone listening for it directly with `@EventHandler` will receive it too — `publish()` calls both `Bukkit.getPluginManager().callEvent(event)` **and** invokes every channel subscriber.

## Publishing

```java
Map<String, Object> data = Map.of("fishId", "golden_koi", "rarity", "LEGENDARY");
eventBus.publish(new SwagCrossPluginMessageEvent(
        "swagfishing:legendary_catch", "SwagFishing", data, player.getUniqueId()));
```

## Subscribing

```java
eventBus.subscribe("swagfishing:legendary_catch", event -> {
    String fishId = (String) event.getData().get("fishId");
    // react to the catch from another plugin's listener, e.g. broadcast, log, award something
}, this);
```

Always pass your own `Plugin` instance as `owner` — it's used by `unsubscribeAll` to clean up your handlers.

## Unsubscribing

```java
@Override
public void onDisable() {
    eventBus.unsubscribeAll(this);
}
```

Call this in your `onDisable()` so a `/reload` or plugin unload doesn't leave stale handler references registered against a dead plugin instance.

## Error isolation

If a subscriber's handler throws, `EventBusService` catches the exception, logs a warning naming the channel and the owning plugin, and continues notifying the remaining subscribers — one broken listener can't block delivery to the others.

## Channel naming convention

There's no enforced namespace, but the convention used internally is `<plugin>:<event-name>` (e.g. `swagfishing:legendary_catch`) to avoid collisions between plugins picking the same short channel name.

## Debug logging

```yaml
event-bus:
  log-events: false
```

Set to `true` to log every published event's channel and source plugin to console — useful when debugging cross-plugin wiring, noisy in production.
