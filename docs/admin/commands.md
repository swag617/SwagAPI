# Admin Commands

SwagAPI exposes a single root command, `/swagapi` (alias `/sapi`), with several subcommands. **Every** subcommand requires `swagapi.admin` — there is no per-subcommand permission split in the current implementation (see [Permissions](permissions.md)).

```
/swagapi <reload|status|info|updatecheck|update|updates|web>
```

## `/swagapi reload`

Reloads `config.yml` and the messaging service's config. Does **not** restart the database connection pool or the HTTP server — changes to `database.*` or `web-server.port`/`bind-address` require a full server restart.

## `/swagapi status`

Prints a live snapshot:
* Configured database type
* HikariCP pool stats (active / idle / total connections)
* Whether Vault/Economy is enabled
* Cached player profile count
* Total event bus subscription count
* Update system status (enabled, pending updates, staged-for-reboot count)
* Web server status (running, port, registered module count)

## `/swagapi info`

Prints the plugin version, the server's Bukkit version string, the list of service interfaces SwagAPI registers, and every currently-loaded plugin that declares SwagAPI as a `depend` or `softdepend`.

## `/swagapi updatecheck`

Triggers an immediate async fetch of the update manifest and reports how many updates are available (or that everything is up to date). See [Update Service](../core-services/update-service.md).

## `/swagapi update <plugin> [--confirm]`

Stages an update for `<plugin>` (must have a cached update from `updatecheck` first).

* **Without `--confirm`**, a player sender gets a clickable `[Confirm]` / `[Cancel]` chat prompt instead of staging immediately; console always proceeds straight to staging.
* **With `--confirm`**, downloads the new jar, verifies its checksum if provided, and moves it into the staging directory. Requires a full server restart to actually apply.

## `/swagapi updates [cancel <plugin>]`

With no arguments, lists every plugin currently staged for the next restart. With `cancel <plugin>`, removes the staged jar and cancels that pending update.

## `/swagapi web [setup|whoami|resetpassword]`

With no arguments, prints the web server's port, bind address, a clickable link to the [Dashboard](../web-panel/dashboard.md), and a clickable link for every registered module.

| Subcommand | Effect |
|---|---|
| `web setup` | Sends the sender (must be a player) a one-time link to create their own web panel account. See [Login & Accounts](../web-panel/login-accounts.md). |
| `web whoami [player]` | Shows which panel account(s) are linked to a player (yourself if omitted; console must specify one). |
| `web resetpassword <username>` | Sends that panel account a password reset link, delivered in-game (immediately if online, queued for next join if offline). |

## Tab completion

All subcommands, staged-plugin names (for `updates cancel`), pending-update plugin names (for `update`), online player names (for `web whoami`), and known panel usernames (for `web resetpassword`) are tab-completable — all gated behind `swagapi.admin` the same as the commands themselves.
