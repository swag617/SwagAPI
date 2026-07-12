# Update Service

`IUpdateService` polls a JSON manifest for new versions of every `Swag*` (and `StackPlus`) plugin on the server, downloads and checksum-verifies updates on request, and applies them on the next restart via a JVM shutdown hook.

## Obtaining it

```java
RegisteredServiceProvider<IUpdateService> rsp =
        Bukkit.getServicesManager().getRegistration(IUpdateService.class);
IUpdateService updates = rsp.getProvider();
```

Most servers interact with this service entirely through the [`/swagapi update*` commands](../admin/commands.md) — direct API use is for plugins that want to build their own update UI.

## Interface

```java
public interface IUpdateService {
    CompletableFuture<List<PluginUpdateInfo>> checkForUpdates();
    List<PluginUpdateInfo> getCachedUpdates();
    Optional<PluginUpdateInfo> getUpdateInfo(String pluginName);
    CompletableFuture<Boolean> stageUpdate(String pluginName);
    boolean isStaged(String pluginName);
    boolean cancelStagedUpdate(String pluginName);
    List<String> getStagedPlugins();
    boolean isEnabled();
}
```

## `PluginUpdateInfo`

```java
public record PluginUpdateInfo(
        String pluginName, String currentVersion, String latestVersion,
        String downloadUrl, String expectedChecksum) {
    boolean isUpdateAvailable();
}
```

## How it works

1. **Startup** — SwagAPI caches the on-disk jar path of every currently-loaded plugin whose name starts with `Swag` (or is exactly `StackPlus`), so it knows what to overwrite later.
2. **Manifest polling** — every `updates.check-interval-hours` (default 6), and optionally once ~2 seconds after startup, SwagAPI fetches `updates.manifest-url` and parses it as:
   ```json
   { "PluginName": { "version": "1.2.3", "url": "https://...", "checksum": "<sha256>" } }
   ```
   Any entry whose `version` doesn't match the currently-loaded plugin's version becomes a cached `PluginUpdateInfo`.
3. **Notification** — if any updates are found, online players with `swagapi.admin` get a clickable chat notification (also re-sent to admins on join, see [Admin Commands](../admin/commands.md)).
4. **Staging** (`stageUpdate` / `/swagapi update <plugin> --confirm`) — downloads the jar to a temp file, verifies its SHA-256 against `expectedChecksum` (if provided), then moves it into `plugins/SwagAPI/staged/<plugin>.staged.jar`.
5. **Applying** — staged jars are **not** applied live. A `UpdateShutdownHook` registered with the JVM copies each staged jar over its target's live jar path when the server process actually exits — i.e. an update only takes effect after a full server restart.
6. **Across restarts** — if the server restarts without the shutdown hook running (crash, kill signal), any `*.staged.jar` files still on disk are re-discovered and re-registered on the next boot, so staged updates aren't silently lost.

## Configuration

```yaml
updates:
  enabled: true
  manifest-url: "https://raw.githubusercontent.com/swag617/swag-versions/main/versions.json"
  check-interval-hours: 6
  notify-on-join: true
  auto-check-on-startup: true
  prefix: "&8[&bSwagAPI&8] &r"
```

| Key | Effect |
|---|---|
| `enabled` | Master switch. When `false`, every `IUpdateService` method is inert and `/swagapi update*` commands report the system as disabled. |
| `manifest-url` | Where the JSON manifest is fetched from. |
| `check-interval-hours` | How often the background poll runs. |
| `notify-on-join` | Whether admins get a chat reminder about pending (unstaged) updates when they join. |
| `auto-check-on-startup` | Whether the first poll runs ~2 seconds after boot instead of waiting a full interval. |
| `prefix` | Chat prefix used on update-related messages. |

## Checksum verification

If `expectedChecksum` is present in the manifest entry, the downloaded jar's SHA-256 must match exactly (case-insensitive) or staging fails and the temp file is deleted. If no checksum is provided, staging proceeds without verification — provide one whenever possible.
