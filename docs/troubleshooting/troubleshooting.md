# Troubleshooting

## Web server won't start

Check the console for:

```
[SwagAPI] Failed to start web server on port <port>: <error>
```

This is almost always a port conflict (another service already bound to `web-server.port`) or an invalid `web-server.bind-address`. Change `web-server.port` in `config.yml` and restart — remember `/swagapi reload` does **not** restart the HTTP server.

## `/swagapi status` shows "Web Server: Stopped"

Either `web-server.enabled: false` in config, or the server failed to bind (see above — check startup console output for the actual error).

## Database fails to initialize

Check console for `Failed to create database tables`. For MySQL, verify `database.mysql.host`/`port`/`database`/`username`/`password` are correct and that the database itself exists (SwagAPI creates tables, not the database/schema itself) and that the configured user has `CREATE TABLE` privileges.

## Login page says "No accounts exist yet"

This is expected on a fresh install — there is no default account. Run `/swagapi web setup` in-game as an operator to create the first one.

## Setup or reset link says "invalid or expired"

Setup links expire after `web-server.auth.setup-token-minutes` (default 15) and are single-use — request a fresh one with `/swagapi web setup` (or `/swagapi web resetpassword <username>` for a password reset). Tokens are held in memory only, so a server restart between requesting and clicking the link also invalidates it.

## A dependent plugin's web module doesn't show up on `/home`

Confirm the dependent plugin actually calls `IWebService#registerModule` successfully — check its own startup log for a line like `[SwagAPI] Registered web module: <name> -> /swagapi/<name>/`. If that line never appears, the dependent plugin either isn't calling `registerModule`, is calling it before SwagAPI's web server has started, or `web-server.enabled` is `false`.

## Economy calls always report "disabled"

`IEconomyService#isEnabled()` requires **both** `economy.enabled: true` in config **and** a registered Vault `Economy` provider. Confirm Vault is installed, an economy plugin (EssentialsX, etc.) that hooks Vault is installed, and both loaded before you're testing — resolution is lazy and re-checked on every call, so a late-loading Vault provider is picked up automatically without a restart.

## Staged plugin update didn't apply after restart

Staged jars are only applied by the JVM shutdown hook when the process exits normally. A hard kill (`kill -9`, task manager "End Process", a crash that bypasses shutdown hooks) can skip this step. Staged jars left on disk in `plugins/SwagAPI/staged/` are automatically re-registered on the next boot, so simply restarting again (cleanly this time) should apply it. You can also check `/swagapi updates` to confirm the update is still staged.

## Checksum mismatch when staging an update

The downloaded jar's SHA-256 didn't match the manifest's `checksum` field. This is a safety check to avoid installing a corrupted or tampered download — the temp file is deleted automatically and the update is not staged. Verify the manifest's checksum is correct for the file at its `url`.

## Still stuck?

* [FAQ](faq.md)
* [Discord](https://discord.gg/9rKuThh6yU)
* [GitHub Issues](https://github.com/swag617/SwagAPI/issues)
