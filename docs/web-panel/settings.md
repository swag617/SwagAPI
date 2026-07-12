# Settings

The `/settings` page is a browser-editable front-end for the `web-server.auth` section of `config.yml`, plus a read-only snapshot of a few other system values — no manual YAML editing required for these specific options.

## Web Panel Settings (editable)

| Field | Config key | Description |
|---|---|---|
| Require login | `web-server.auth.enabled` | Unchecking this disables the login requirement for **every** route on the server, including every registered plugin module. |
| Remind OPs with no account to create one on join | `web-server.auth.remind-ops-on-join` | Toggles the automatic setup-link chat prompt described in [Login & Accounts](login-accounts.md). |
| Session length (days) | `web-server.auth.session-days` | How long a login session lasts; slides forward on every authenticated request. |
| Setup/reset link expiry (minutes) | `web-server.auth.setup-token-minutes` | How long an account-setup or password-reset link stays valid before it must be re-requested. |

Saving this form does **not** perform a full `plugin.reloadConfig()` of everything — it makes a targeted text edit to only the four `web-server.auth.*` lines in `config.yml` (preserving every comment and every other setting in the file), then reloads just the config and re-applies the new auth values to the running web server immediately. No restart is required for these four settings.

## System Info (read-only)

| Field | Source |
|---|---|
| Database type | `database.type` |
| Economy (Vault) | `IEconomyService#isEnabled()` |
| Update system | `IUpdateService#isEnabled()` |
| Web server port | Active bound port |
| Web server bind address | Active bind address |
| Registered web modules | Current count from `IWebService#getRegisteredModules()` |

These fields reflect live values, not just what's written in `config.yml` — e.g. "Economy (Vault)" shows whether a provider is actually resolved right now, not just whether `economy.enabled` is `true`.

## Changing values not exposed here

Anything outside `web-server.auth.*` (database type/credentials, web server port/bind-address/threads, economy toggle, update manifest settings, messaging prefix) must still be edited directly in `plugins/SwagAPI/config.yml`, followed by `/swagapi reload` (config only) or a full restart for values that affect the connection pool or the HTTP server's bind socket.
