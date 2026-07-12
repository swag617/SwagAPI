# Player Data Service

`IPlayerDataService` maintains one cached `SwagPlayerProfile` per online player and gives every dependent plugin a place to attach its own load/save logic without hooking join/quit events itself.

## Obtaining it

```java
RegisteredServiceProvider<IPlayerDataService> rsp =
        Bukkit.getServicesManager().getRegistration(IPlayerDataService.class);
IPlayerDataService playerData = rsp.getProvider();
```

## Interface

```java
public interface IPlayerDataService {
    SwagPlayerProfile getProfile(UUID uuid);
    SwagPlayerProfile getProfile(Player player);
    boolean isLoaded(UUID uuid);
    CompletableFuture<SwagPlayerProfile> loadProfile(UUID uuid);
    CompletableFuture<Void> saveProfile(UUID uuid);
    void saveAll();
    void registerModule(String pluginKey, PlayerDataModule module);
    <T> T getModuleData(UUID uuid, String pluginKey, Class<T> type);
    void setModuleData(UUID uuid, String pluginKey, Object data);
}
```

## The profile lifecycle

SwagAPI's own `PlayerSessionListener` drives the profile lifecycle automatically — you don't call `loadProfile`/`saveProfile` yourself in most cases:

1. **`PlayerLoginEvent` (MONITOR)** — `loadProfile(uuid)` is called. This reads (or creates) the row in `swagapi_players`, then calls `load()` on every registered `PlayerDataModule` in parallel and waits for all of them (`CompletableFuture.allOf(...).join()`). Once complete, a `SwagPlayerDataLoadEvent` Bukkit event fires on the main thread.
2. **While online** — the profile sits in an in-memory cache (`ConcurrentHashMap<UUID, SwagPlayerProfile>`). A background task also saves every cached profile every `player-data.auto-save-interval-minutes` (default 5).
3. **`PlayerQuitEvent` (MONITOR)** — `saveProfile(uuid)` is called: the player row is upserted, `save()` runs on every module for that player, and a `SwagPlayerDataSaveEvent` fires.
4. **`onDisable`** — `saveAll()` is called, which cancels the auto-save task, force-saves every cached profile, and clears the cache.

## `SwagPlayerProfile`

```java
public class SwagPlayerProfile {
    UUID getUuid();
    String getUsername();
    void setUsername(String username);
    long getFirstJoin();
    void setFirstJoin(long firstJoin);
    long getLastSeen();
    void setLastSeen(long lastSeen);
    Object getModuleData(String key);
    void setModuleData(String key, Object data);
    Map<String, Object> getAllModuleData();
}
```

The core fields (`uuid`, `username`, `firstJoin`, `lastSeen`) are persisted by SwagAPI itself in the `swagapi_players` table. Everything else lives in the profile's `moduleData` map, keyed by your plugin's key string.

## Registering a `PlayerDataModule`

```java
public interface PlayerDataModule {
    CompletableFuture<Object> load(UUID uuid, IDatabaseService db);
    CompletableFuture<Void> save(UUID uuid, Object data, IDatabaseService db);
}
```

```java
playerData.registerModule("myplugin", new PlayerDataModule() {
    @Override
    public CompletableFuture<Object> load(UUID uuid, IDatabaseService db) {
        return db.queryAsync(() -> {
            // query your own table(s) using db.getConnection()
            return myLoadedData;
        });
    }

    @Override
    public CompletableFuture<Void> save(UUID uuid, Object data, IDatabaseService db) {
        return CompletableFuture.runAsync(() -> {
            // persist `data` using db.getConnection()
        });
    }
});
```

`load()` is called for every module on every profile load; if it returns non-null, the result is stored under your `pluginKey` in the profile's module data. `save()` is only called if `getModuleData` for your key is non-null at save time.

## Reading and writing your module's cached data

```java
MyData data = playerData.getModuleData(uuid, "myplugin", MyData.class);
if (data != null) {
    data.setSomething(...);
    playerData.setModuleData(uuid, "myplugin", data);
}
```

`getModuleData` returns `null` if the profile isn't loaded, has no data under that key, or the stored object isn't an instance of the requested type.

## Configuration

```yaml
player-data:
  cache-expiry-minutes: 10
  auto-save-interval-minutes: 5
```

> `cache-expiry-minutes` is a configured value but profile eviction in the current implementation happens on quit (via `saveProfile`), not on a separate expiry timer — the cache is keyed to online players in practice.

## Related events

* `SwagPlayerDataLoadEvent(UUID uuid, SwagPlayerProfile profile)` — fired after a profile and all its modules finish loading.
* `SwagPlayerDataSaveEvent(UUID uuid, SwagPlayerProfile profile)` — fired after a profile and all its modules finish saving.

Both are ordinary Bukkit events fired on the main thread — listen with a normal `@EventHandler`.
