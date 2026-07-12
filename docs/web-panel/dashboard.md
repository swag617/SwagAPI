# Dashboard

The `/home` page is the master control panel for the entire Swag617 web ecosystem — it's the page you land on after logging in (or immediately, if auth is disabled).

## Layout

**Sidebar**
* **Account** — link to [Manage Account](login-accounts.md)
* **Settings** — link to [Web Panel Settings](settings.md)
* **Links** — GitHub, Discord
* **Wikis** — Swag617 home, plugin doc sites
* Collapsible — your collapsed/expanded preference is remembered in `localStorage`

**Plugin Modules**
A live grid of every plugin currently registered via `IWebService#registerModule` (SwagFishing, SwagBounties, etc., whenever they're installed and have registered a web module). Each card shows an "Active" badge and an "Open →" button linking to `/swagapi/<module>/`. If nothing is registered yet, it shows an empty-state message instead.

**Server Info**
Software version, uptime, HTTP port, total plugin count, registered web module count, TPS (5m/15m average), active panel sessions, and server MOTD — all pulled live from `/api/status`.

**Worlds**
A table of every loaded world: name, environment (normal/nether/the_end), and currently loaded chunk count.

**Loaded Plugins**
Every plugin on the server, shown as a green dot (enabled) or red dot (disabled) with its version on hover.

**Footer**
Quick links to the raw `/swagapi/` info JSON and `/api/status` JSON, a live-updating timestamp, and a `swag617` wordmark link.

## Data source

Everything on `/home` past the static shell is populated client-side by polling `GET /api/status`, which returns:

```json
{
  "tps": { "5m": "19.8", "15m": "19.9" },
  "uptime": 123456,
  "sessions": 2,
  "motd": "A Minecraft Server",
  "version": "...",
  "port": 8080,
  "plugins": [ { "name": "SwagFishing", "version": "1.0.0", "enabled": true }, ... ],
  "modules": [ "swagfishing", "swagbounties" ],
  "worlds": [ { "name": "world", "env": "NORMAL", "chunks": 42 }, ... ]
}
```

The shared `topbar.js`/`topbar.css` (served from `/swagapi/shared/`) owns this poll and broadcasts a `swag-status` browser event that `/home` and every other dashboard page listen for — so the Players/TPS/Memory/Uptime chips in the top bar and the page-specific content below it always stay in sync from one fetch.

## Access

`/home` requires an authenticated session unless `web-server.auth.enabled` is `false` in config. See [Login & Accounts](login-accounts.md) for how accounts are created.
