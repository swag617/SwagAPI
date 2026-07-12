# Installation

SwagAPI is a standalone Paper plugin — it does not require any other Swag617 plugin to be installed first. Other Swag617 plugins (SwagFishing, SwagBounties, SwagCore, etc.) require **SwagAPI** to be present.

## Requirements

| Dependency | Required |
|---|---|
| Paper / Spigot 1.21+ | Yes |
| Java 17+ | Yes |
| Vault | No — only needed for the Economy Service |
| MySQL server | No — SQLite is the default, bundled database |

## Steps

1. Download or build `SwagAPI-1.0.0.jar`.
2. Drop it into your server's `plugins/` folder.
3. Start (or restart) the server. On first boot SwagAPI will:
   * Generate `plugins/SwagAPI/config.yml` with sensible defaults
   * Create a SQLite database file (`swagapi.db` by default) and its tables
   * Start the shared web server on port `8080` (configurable)
4. Confirm it loaded by running `/swagapi status` in-game or from console.

> **Tip:** The default admin permission is `swagapi.admin`, which defaults to **op**. Anyone with server-operator status can use every `/swagapi` subcommand out of the box.

## Configuring the database

By default SwagAPI uses SQLite — no setup required. To switch to MySQL, edit `config.yml`:

```yaml
database:
  type: mysql
  mysql:
    host: localhost
    port: 3306
    database: swagapi
    username: root
    password: ""
    pool-size: 10
    connection-timeout: 30000
    idle-timeout: 600000
    max-lifetime: 1800000
    use-ssl: false
```

Then run `/swagapi reload` or restart — note that `/swagapi reload` reloads `config.yml` and messages, but **does not** restart the database connection pool. A full server restart is required to actually switch database types.

## Enabling economy support

Economy features are enabled by default but require [Vault](https://www.spigotmc.org/resources/vault.34315/) plus an economy plugin (e.g. EssentialsX) to actually function:

```yaml
economy:
  enabled: true
```

With no Vault economy provider registered, `IEconomyService#isEnabled()` returns `false` and every economy method safely no-ops (withdraw/deposit report success, balance reads as `0.0`) instead of throwing.

## The shared web panel

The web panel is enabled by default on port `8080`, bound to all interfaces (`0.0.0.0`). See [For Developers](for-developers.md) for how other plugins hook into it, or the [Web Panel](../web-panel/dashboard.md) section for a tour of what it looks like out of the box.

To create your first web panel login, run in-game as an operator:

```
/swagapi web setup
```

This sends you a clickable, one-time link (valid for 15 minutes by default) to create your account in the browser.

## Next Steps

* [For Developers](for-developers.md) — depending on SwagAPI from your own plugin
* [Admin Commands](../admin/commands.md) — the full `/swagapi` command reference
* [Web Panel → Dashboard](../web-panel/dashboard.md) — tour of the `/home` control panel
