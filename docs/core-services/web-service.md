# Web Service

`IWebService` is the single HTTP server shared by the entire Swag617 ecosystem. Instead of every plugin opening its own port, plugins register a handler with SwagAPI and get mounted under `/swagapi/<plugin-name>/` on SwagAPI's port, behind SwagAPI's own login session.

## Obtaining it

```java
RegisteredServiceProvider<IWebService> rsp =
        Bukkit.getServicesManager().getRegistration(IWebService.class);
IWebService web = rsp.getProvider();
```

## Interface

```java
public interface IWebService {
    void registerModule(Plugin plugin, HttpHandler handler);
    void unregisterModule(Plugin plugin);
    boolean isRunning();
    int getPort();
    String getBindAddress();
    List<String> getRegisteredModules();
    String getPluginUrl(String moduleName);
    Optional<String> getSessionUsername(HttpExchange exchange);
}
```

| Method | Description |
|---|---|
| `registerModule(plugin, handler)` | Mounts `handler` at `/swagapi/<plugin-name-lowercased>/`. A no-trailing-slash redirect is installed automatically. The mount point is already gated by SwagAPI's session — your handler only runs for authenticated requests (or always, if auth is disabled). |
| `unregisterModule(plugin)` | Removes your handler. Call this from `onDisable()`. Silently no-ops if nothing was registered or the server isn't running. |
| `isRunning()` | Whether the HTTP server is currently accepting connections. |
| `getPort()` / `getBindAddress()` | The configured port/bind address, even if the server failed to start. |
| `getRegisteredModules()` | Snapshot list of registered module names, in registration order. |
| `getPluginUrl(moduleName)` | Builds the full browser URL for a module, e.g. `http://192.168.1.10:8080/swagapi/swagfishing/`. |
| `getSessionUsername(exchange)` | Resolves who's logged in for the current request. Since your module route is already gated, this is normally just for personalizing output ("Signed in as X"), not re-checking auth. |

## Registering a module

```java
@Override
public void onEnable() {
    RegisteredServiceProvider<IWebService> rsp =
            Bukkit.getServicesManager().getRegistration(IWebService.class);
    if (rsp != null) {
        rsp.getProvider().registerModule(this, exchange -> {
            String path = exchange.getRequestURI().getPath(); // prefix already stripped
            // handle the request — path arrives as e.g. "/status" for
            // a request to /swagapi/myplugin/status
        });
    }
}

@Override
public void onDisable() {
    RegisteredServiceProvider<IWebService> rsp =
            Bukkit.getServicesManager().getRegistration(IWebService.class);
    if (rsp != null) rsp.getProvider().unregisterModule(this);
}
```

## Checking login status from the browser (client-side)

Rather than re-implementing auth, poll the ungated JSON endpoint from your module's front-end JavaScript:

```
GET /swagapi/auth/status
→ {"authEnabled": true, "authenticated": true, "username": "Swag"}
```

This endpoint never redirects, so a page can detect an expired session itself and bounce the user to `/login?redirect=<path>`.

## Built-in routes (all served by SwagAPI itself)

| Route | Auth-gated | Purpose |
|---|---|---|
| `GET /` | — | Redirects to `/home`. |
| `GET/POST /login` | — | Login form / credential check. |
| `GET /logout` | — | Clears the session cookie, redirects to `/login`. |
| `GET/POST /setup` | — | One-time account-creation link (from `/swagapi web setup`). |
| `GET/POST /forgot-password` | — | Requests a password reset link delivered in-game. |
| `GET/POST /reset-password` | — | Consumes a reset token and sets a new password. |
| `GET /home` | Yes | The master control panel dashboard. See [Dashboard](../web-panel/dashboard.md). |
| `GET/POST /account` | Yes | Change your own password; admin table of all accounts. |
| `POST /account/reset` | Yes | Trigger a reset link for another account from the table. |
| `GET/POST /settings` | Yes | Editable auth settings + read-only system info. |
| `GET /api/status` | Yes | Live JSON server stats (TPS, memory, players, worlds, plugins, modules). |
| `GET /swagapi/` | — | Plain JSON: `{status, plugin, version, port, modules}`. |
| `GET /swagapi/modules` | — | JSON array of registered module names. |
| `GET /swagapi/auth/status` | — | Never-redirecting login-status JSON, for client-side polling. |
| `GET /swagapi/shared/topbar.css` / `.js` | — | The shared navigation chrome every dashboard page includes by reference. |
| `/swagapi/<your-plugin>/...` | Yes | Your registered module. |

## Configuration

```yaml
web-server:
  enabled: true
  port: 8080
  bind-address: "0.0.0.0"
  threads: 8
  auth:
    enabled: true
    session-days: 30
    setup-token-minutes: 15
    remind-ops-on-join: true
```

| Key | Effect |
|---|---|
| `enabled` | Master switch for the entire HTTP server. When `false`, `IWebService` is still registered but every method reports the server as not running. |
| `port` / `bind-address` | Standard socket bind options. |
| `threads` | Size of the fixed thread pool handling requests. |
| `auth.enabled` | When `false`, every route (including your registered modules) is served with **no login check at all** — only disable this on a port you don't expose publicly. |
| `auth.session-days` | Login session lifetime; each authenticated request slides the expiry forward. |
| `auth.setup-token-minutes` | How long a `/swagapi web setup` or password-reset link stays valid. |
| `auth.remind-ops-on-join` | Nags OPs with `swagapi.web` and no linked account, once per join, with a clickable account-setup link. |

## Session mechanics

* Sessions are a random 32-byte hex token stored server-side (`swag_session` cookie, `HttpOnly`, `SameSite=Lax`) — not a JWT, so revocation is instant (`/logout` just removes the server-side entry).
* Passwords are hashed with **PBKDF2WithHmacSHA256** (65,536 iterations, 256-bit output) and a random 16-byte salt per account, verified with a constant-time comparison.
* Account records persist to `plugins/SwagAPI/accounts.yml`, each optionally linked to a player `UUID`.
* Non-browser (JSON) requests that fail auth get a `401` instead of a redirect, detected via the `Accept: application/json` header.
* `?redirect=` targets are sanitized to same-origin relative paths only — protects against open-redirect abuse of the login flow.

See [Login & Accounts](../web-panel/login-accounts.md) for the full account lifecycle from a server-owner's perspective.
