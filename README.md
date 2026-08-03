# SwagAPI - Shared Backbone for the Swag617 Plugin Ecosystem

Shared API and communication backbone for every plugin in the Swag617 ecosystem (SwagFishing, SwagRestartScheduler, SwagBounties, and friends). SwagAPI isn't a gameplay plugin — it centralizes the handful of problems every one of those plugins used to solve separately: database pooling, economy hooks, player data caching, cross-plugin messaging, update delivery, and a shared web dashboard.

## Features

### Core Services
- **Database Service** — one HikariCP connection pool (SQLite or MySQL) shared by every dependent plugin, plus async query helpers
- **Economy Service** — a thin, null-safe Vault bridge so no plugin has to check for Vault itself
- **Player Data Service** — a cached `SwagPlayerProfile` per player that any plugin can attach a `PlayerDataModule` to for its own load/save logic
- **Messaging Service** — shared color code, placeholder, title, and action bar helpers built on Adventure Components
- **Event Bus Service** — an in-process publish/subscribe channel for cross-plugin communication without a hard compile dependency
- **Update Service** — manifest-driven update checks, checksum-verified downloads, and staged jar swaps applied by a JVM shutdown hook

### Web Service
- A single embedded HTTP server (Java's built-in `HttpServer`, no extra dependency) shared by every plugin — nobody opens their own port
- Human-facing modules mount under `/swagapi/<plugin-name>/` via `IWebService#registerModule`, gated by SwagAPI's own cookie-session login system (PBKDF2-hashed passwords, no plugin re-implements its own login form)
- **Server-to-server modules** mount under `/swagnet/<plugin-name>/` via `IWebService#registerServiceModule` — for network calls between servers (e.g. a hub server reading another server's stats), gated instead by a shared-secret header (`X-SwagNetwork-Key`) checked against `network.shared-secret`, fails closed if unconfigured
- A shared master dashboard (`/home`), account management (`/account`), and live settings editor (`/settings`)
- Self-service account setup/password reset flows delivered via clickable in-game chat links

## Requirements

| Dependency | Required |
|---|---|
| Paper / Spigot 1.21+ | Yes |
| Java 17+ | Yes |
| Vault | No — economy features no-op without it |
| An economy plugin (EssentialsX, etc.) | No — only needed if using the Economy Service |
| MySQL server | No — SQLite is the default, bundled database |

> SwagAPI ships its own SQLite driver, HikariCP, MySQL connector, and Gson (all relocated/shaded into the jar) — no separate downloads needed for those.

## Building

### Prerequisites
- Java JDK 17+
- Maven 3.8+

### Build Commands

```bash
# Clone the repository
git clone https://github.com/swag617/SwagAPI.git
cd SwagAPI

# Clean and package
mvn clean package

# Output JAR will be in: target/SwagAPI-1.0.1.jar
```

Drop the resulting jar into your server's `plugins/` folder. No other plugin is required for the core services; other Swag617 plugins declare SwagAPI as a hard or soft dependency depending on how tightly they rely on it.

## Project Structure

```
SwagAPI/
├── pom.xml                                     # Maven build configuration
├── src/main/
│   ├── java/com/SwagDev/SwagAPI/
│   │   ├── SwagAPI.java                        # Main plugin class — registers all services
│   │   ├── api/                                # Public service interfaces (IDatabaseService, IWebService, ...)
│   │   ├── commands/
│   │   │   └── SwagAPICommand.java             # /swagapi admin command
│   │   ├── database/                           # HikariCP-backed MySQL/SQLite managers
│   │   ├── events/                             # Cross-plugin Bukkit events
│   │   ├── listeners/                          # Join/update/web-account listeners
│   │   ├── model/                              # Shared data models (SwagPlayerProfile, etc.)
│   │   ├── services/                           # Service implementations (DatabaseService, WebService, ...)
│   │   └── util/                               # Color, item, message, and scheduler helpers
│   └── resources/
│       ├── plugin.yml                          # Plugin metadata
│       ├── config.yml                          # Main configuration
│       └── messages.yml                        # Player-facing messages
└── docs/                                        # Docsify documentation site
```

## Documentation

Full documentation — installation, the web panel, every core service, and admin commands — is published at:

**https://swag617.github.io/SwagAPI/**

## Downloads

Prebuilt releases are published on GitHub:

**https://github.com/swag617/SwagAPI/releases**

## License

Proprietary software developed for the Swag617 plugin ecosystem. All rights reserved.
