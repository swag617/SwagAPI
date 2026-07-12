# Messaging Service

`IMessagingService` centralizes chat color codes, placeholder substitution, titles, and action bars so every Swag617 plugin's player-facing text is built the same way, on top of Adventure Components.

## Obtaining it

```java
RegisteredServiceProvider<IMessagingService> rsp =
        Bukkit.getServicesManager().getRegistration(IMessagingService.class);
IMessagingService messaging = rsp.getProvider();
```

## Interface

```java
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
```

| Method | Description |
|---|---|
| `send(player, message)` | Colorizes (`&`-style codes) and sends a chat message. |
| `send(player, message, placeholders)` | Replaces every `{key}` in `message` with the matching value from `placeholders` before colorizing and sending. |
| `broadcast(message)` | Sends a colorized message to every online player. |
| `broadcastPermission(message, permission)` | Same as `broadcast`, but only to players holding `permission`. |
| `sendTitle(player, title, subtitle, fadeIn, stay, fadeOut)` | Shows a title/subtitle; fade/stay/fade-out are in ticks, converted to `Duration` internally (50ms per tick). |
| `sendActionBar(player, message)` | Colorizes and shows a message in the action bar. |
| `colorize(input)` | Returns the legacy-formatted `String` for `&`-code input — useful when you need a `String`, not a `Component` (e.g. for `CommandSender#sendMessage(String)`). |
| `toComponent(input)` | Returns an Adventure `Component` for the same input — use this when building richer messages (hover text, click events) that get `.append()`ed to. |

## Placeholder substitution format

Placeholders use `{key}` syntax, not `%key%`:

```java
Map<String, String> placeholders = Map.of("player", target.getName(), "amount", "100");
messaging.send(sender, "&aSent {amount} to {player}!", placeholders);
```

## Example: building a message with a clickable component

For anything beyond plain colorized text, use `toComponent()` and Adventure's own builder API (the same pattern `SwagAPICommand` uses for its own clickable prompts):

```java
Component base = messaging.toComponent("&7Click to confirm: ");
Component button = Component.text("[Confirm]", NamedTextColor.GREEN)
        .clickEvent(ClickEvent.runCommand("/myplugin confirm"));
player.sendMessage(base.append(button));
```

## Configuration

```yaml
messaging:
  prefix: "&8[&bSwagAPI&8] &r"
  date-format: "MM/dd/yyyy HH:mm"
```

These two keys back SwagAPI's own internal messages (e.g. update notifications); they are not automatically prepended to messages sent through `IMessagingService` by dependent plugins — build your own prefix into the strings you pass in.
