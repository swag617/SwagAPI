# FAQ

**Do I need Vault installed?**
No. `economy.enabled` is `true` by default, but if Vault (or an economy plugin) isn't present, `IEconomyService#isEnabled()` simply returns `false` and every economy method behaves as a safe no-op instead of erroring.

**Do I need MySQL?**
No. SQLite is the default and is bundled — no external database server required. Switch to MySQL only if you want to share the database across multiple servers.

**Why doesn't `/swagapi reload` pick up my new database settings?**
`/swagapi reload` reloads `config.yml` and messaging config only. It does not rebuild the HikariCP connection pool or restart the HTTP server — changes to `database.*` or `web-server.port`/`bind-address` need a full server restart.

**How do I create my first web panel login?**
Run `/swagapi web setup` in-game as a player with `swagapi.admin`. There's no default account — the first one has to be created through this one-time link flow. See [Login & Accounts](../web-panel/login-accounts.md).

**I forgot my web panel password. What do I do?**
Use the "Forgot password?" link on `/login`, or have another admin run `/swagapi web resetpassword <username>`. The reset link is delivered to your in-game chat — immediately if you're online, or the next time you join if you're offline.

**Can I run the web panel with no login at all?**
Yes — set `web-server.auth.enabled: false` (or toggle "Require login" off on the [Settings](../web-panel/settings.md) page). This removes authentication from every route, including every registered plugin module. Only do this if the port isn't reachable from outside your trusted network.

**Why can I run `/swagapi web whoami` but my staff member can't run `/swagapi web setup`?**
Both are gated by the same `swagapi.admin` permission — there's currently no way to split access within the `web` subcommand tree. See [Permissions](../admin/permissions.md) for the full breakdown of what's actually enforced.

**Does SwagAPI update itself?**
The update system checks and stages updates for any plugin named `Swag*` or `StackPlus` whose manifest entry has a newer version — including SwagAPI itself, if it appears in the manifest. Staged updates are only applied when the JVM actually exits (restart), never live.

**Where does the web panel run from — do I need a reverse proxy?**
No, it's a plain embedded `HttpServer` bound directly to the configured port (`8080` by default) and IP (`0.0.0.0` by default). A reverse proxy (nginx, Caddy) is optional if you want TLS/HTTPS in front of it — SwagAPI itself serves plain HTTP.
