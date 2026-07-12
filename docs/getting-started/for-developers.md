# For Developers

This page is for anyone writing a Swag617 sibling plugin (or any Paper plugin) that wants to depend on SwagAPI instead of reimplementing a database pool, Vault hook, or HTTP server.

## 1. Declare the dependency

In your plugin's `plugin.yml`, declare a hard or soft dependency on SwagAPI so it loads first:

```yaml
depend: [SwagAPI]
# or, if your plugin can run with reduced functionality without it:
# softdepend: [SwagAPI]
```

Add the SwagAPI jar to your build classpath as a `provided`-scope dependency (it's not shaded into your plugin — it's installed as its own plugin on the server) and make sure the jar is available to your local Maven build (e.g. installed to your local repository, or referenced as a local jar).

## 2. Obtain a service via Bukkit's ServicesManager

Every SwagAPI capability is exposed as an interface in `com.SwagDev.SwagAPI.api` and registered with Bukkit's `ServicesManager` in `SwagAPI#onEnable`. Pull whichever ones you need:

```java
import com.SwagDev.SwagAPI.api.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

RegisteredServiceProvider<IDatabaseService> dbRsp =
        Bukkit.getServicesManager().getRegistration(IDatabaseService.class);
if (dbRsp != null) {
    IDatabaseService db = dbRsp.getProvider();
    // use db.getConnection(), db.queryAsync(...), etc.
}
```

The same pattern works for `IEconomyService`, `IPlayerDataService`, `IMessagingService`, `IEventBusService`, `IUpdateService`, and `IWebService`. All seven are registered with `ServicePriority.Normal` during `SwagAPI#onEnable`, so grab them in your own `onEnable` (after confirming SwagAPI is a hard/soft dependency so load order is guaranteed).

> **Null-check every lookup.** `getRegistration()` returns `null` if SwagAPI isn't installed (relevant if you used `softdepend`) or hasn't finished enabling yet.

## 3. Storing your own player data

Rather than opening your own database connection, register a `PlayerDataModule` with the Player Data Service. SwagAPI calls `load()` when a profile is loaded (on player login) and `save()` on quit and on its periodic auto-save timer — you never have to hook `PlayerJoinEvent`/`PlayerQuitEvent` yourself for this:

```java
public class MyPlayerDataModule implements PlayerDataModule {
    @Override
    public CompletableFuture<Object> load(UUID uuid, IDatabaseService db) {
        // read your plugin's own row(s) using db.getConnection() / db.queryAsync()
    }

    @Override
    public CompletableFuture<Void> save(UUID uuid, Object data, IDatabaseService db) {
        // persist `data` (whatever object your load() returned/updated)
    }
}

playerDataService.registerModule("myplugin", new MyPlayerDataModule());
```

Read the cached value back at any time with `playerDataService.getModuleData(uuid, "myplugin", MyDataType.class)`, and mutate it with `setModuleData(...)`. See [Player Data Service](../core-services/player-data-service.md) for the full lifecycle.

## 4. Cross-plugin events

Two kinds of events are available:

* **Bukkit events** — `SwagPlayerDataLoadEvent`, `SwagPlayerDataSaveEvent`, and `SwagEconomyTransactionEvent` are fired as normal Bukkit events (`com.SwagDev.SwagAPI.events`). Listen to them like any other `Listener`.
* **`IEventBusService`** — a lighter-weight, channel-based publish/subscribe system for ad-hoc cross-plugin messages that don't warrant a dedicated event class. See [Event Bus Service](../core-services/event-bus-service.md).

## 5. Hosting a web dashboard for your plugin

If your plugin wants a browser-based admin UI, don't start your own `HttpServer` — register a handler with SwagAPI's shared one:

```java
RegisteredServiceProvider<IWebService> webRsp =
        Bukkit.getServicesManager().getRegistration(IWebService.class);
if (webRsp != null) {
    IWebService web = webRsp.getProvider();
    web.registerModule(this, exchange -> {
        // your HttpHandler — mounted at /swagapi/<yourplugin>/
        // by the time this runs, the request is already authenticated
        // (or auth is disabled) — do not implement your own login screen
    });
}
```

Your handler receives requests with the `/swagapi/<yourplugin>/` prefix already stripped, and SwagAPI's session cookie already verified. Call `web.unregisterModule(this)` in your `onDisable`. See [Web Service](../core-services/web-service.md) for the full contract, including how to check who's logged in and how to build a "Sign in as X" indicator client-side via `/swagapi/auth/status`.

## Next Steps

* [Core Services](../core-services/database-service.md) — full reference for every service interface
* [Web Service](../core-services/web-service.md) — module registration contract in detail
