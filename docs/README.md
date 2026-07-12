# SwagAPI <small>v1.0.0</small>

> **SwagAPI** is the shared library and core-services API that every plugin in the Swag617 ecosystem (SwagFishing, SwagBounties, SwagCore, and friends) depends on for database access, economy, player data, messaging, cross-plugin events, updates, and the shared web panel.

## What Makes SwagAPI Special?

SwagAPI isn't a gameplay plugin — it's the backbone other Swag plugins build on. It solves the same handful of problems every one of them used to solve separately:

* **Database Service** — one HikariCP connection pool (SQLite or MySQL) shared by every dependent plugin, plus async query helpers
* **Economy Service** — a thin, null-safe Vault bridge so no plugin has to check for Vault itself
* **Player Data Service** — a cached `SwagPlayerProfile` per player that any plugin can attach a `PlayerDataModule` to for its own load/save logic
* **Messaging Service** — shared color code, placeholder, title, and action bar helpers built on Adventure Components
* **Event Bus Service** — an in-process publish/subscribe channel for cross-plugin communication without a hard compile dependency
* **Update Service** — manifest-driven update checks, checksum-verified downloads, and staged jar swaps applied by a JVM shutdown hook
* **Web Service** — a single embedded HTTP server hosting a shared dashboard, login system, and per-plugin module mounts under `/swagapi/<plugin>/`

## Core Philosophy

### One Backbone, Many Plugins
Every Swag617 plugin registers with SwagAPI's `ServicesManager` entries instead of standing up its own database pool, its own Vault hook, or its own HTTP server. Add a dependency, pull a service, move on.

### The Web Panel Is Shared, Not Duplicated
SwagAPI owns the only HTTP server and the only login/session system in the ecosystem. A dependent plugin calls `IWebService#registerModule` and its handler is already sitting behind SwagAPI's cookie session — it never implements its own password form.

### Fully Optional Integrations
Vault, MySQL, and the update manifest are all optional. `IEconomyService#isEnabled()` and `IUpdateService#isEnabled()` let dependent plugins check availability instead of assuming it.

## Quick Links

| Feature | Description | Link |
|---------|-------------|------|
| **Installation** | Get SwagAPI running on your server | [Installation Guide](getting-started/installation.md) |
| **For Developers** | Depend on SwagAPI from your own plugin | [For Developers](getting-started/for-developers.md) |
| **Web Panel** | Tour the shared dashboard | [Dashboard](web-panel/dashboard.md) |
| **Admin Commands** | Full `/swagapi` reference | [Admin Commands](admin/commands.md) |

## Requirements

| Dependency | Required |
|---|---|
| Paper / Spigot 1.21+ | Yes |
| Java 17+ | Yes |
| Vault | No — economy features no-op without it |
| An economy plugin (EssentialsX, etc.) | No — only needed if using the Economy Service |
| MySQL server | No — SQLite is the default, bundled database |

> SwagAPI ships its own SQLite driver, HikariCP, MySQL connector, and Gson (all relocated/shaded into the jar) — no separate downloads needed for those.

## Credits

**Developer:** Swag617
**Built With:** Java 17, Paper API, HikariCP, Gson, SQLite JDBC, MySQL Connector/J

## License

SwagAPI is proprietary software developed for the Swag617 plugin ecosystem.
All rights reserved © 2026

---

> **Need Help?** Check out our [FAQ](troubleshooting/faq.md) or [Troubleshooting](troubleshooting/troubleshooting.md) page!
