package com.SwagDev.SwagAPI.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

/**
 * Shared HTTP web service provided by SwagAPI.
 *
 * <p>Allows any Swag-ecosystem plugin to expose an HTTP endpoint under the shared
 * {@code /swagapi/<plugin-name>/} path without starting its own server.
 * The server is started once in SwagAPI and all modules share its port and thread pool.</p>
 *
 * <p>Obtain via Bukkit's ServicesManager:</p>
 * <pre>{@code
 * RegisteredServiceProvider<IWebService> rsp =
 *         Bukkit.getServicesManager().getRegistration(IWebService.class);
 * if (rsp != null) {
 *     IWebService web = rsp.getProvider();
 *     web.registerModule(this, myHandler);
 * }
 * }</pre>
 */
public interface IWebService {

    /**
     * Registers an HTTP handler for the given plugin.
     *
     * <p>The handler is mounted at {@code /swagapi/<plugin-name>/}. The prefix is
     * stripped before the handler receives the exchange, so a request to
     * {@code /swagapi/myplugin/status} arrives at the handler as {@code /status}.</p>
     *
     * <p>A redirect from {@code /swagapi/<plugin-name>} (without trailing slash) to
     * the canonical form with trailing slash is installed automatically.</p>
     *
     * <p>The mount point is automatically gated by SwagAPI's own session/login system
     * (see {@link #getSessionUsername}) — dependent plugins should <b>not</b> implement
     * their own password/login screen. By the time your handler runs, the request is
     * already authenticated (or auth is disabled entirely).</p>
     *
     * @param plugin  the plugin registering the module; its name (lower-cased) becomes
     *                the path segment
     * @param handler the {@link HttpHandler} that will receive stripped requests
     */
    void registerModule(Plugin plugin, HttpHandler handler);

    /**
     * Removes the HTTP handler registered by the given plugin.
     *
     * <p>Silently does nothing if the plugin has no registered module or if the
     * web server is not running.</p>
     *
     * @param plugin the plugin whose module should be removed
     */
    void unregisterModule(Plugin plugin);

    /**
     * Returns whether the web server is currently accepting connections.
     *
     * @return {@code true} if the server started successfully and has not been shut down
     */
    boolean isRunning();

    /**
     * Returns the TCP port the web server is bound to.
     *
     * <p>Returns the configured port value even if the server failed to start.</p>
     *
     * @return the port number
     */
    int getPort();

    /**
     * Returns the bind-address string as configured (e.g. {@code "0.0.0.0"}).
     *
     * @return the bind address
     */
    String getBindAddress();

    /**
     * Returns an unmodifiable snapshot of all currently registered module names.
     *
     * <p>Each entry is the lower-cased plugin name (e.g. {@code "swagfishing"}).</p>
     *
     * @return list of module names in registration order
     */
    List<String> getRegisteredModules();

    /**
     * Returns the full base URL for a registered module.
     *
     * <p>Example: {@code http://192.168.1.10:8080/swagapi/swagfishing/}</p>
     *
     * @param moduleName the module name as returned by {@link #getRegisteredModules()}
     * @return the fully-qualified URL with trailing slash, using the server's display IP
     */
    String getPluginUrl(String moduleName);

    /**
     * Resolves the SwagAPI panel account signed in on the given request, if any.
     *
     * <p>This is the single source of truth for "who is logged in" across the whole
     * Swag ecosystem's shared web panel. Module handlers registered via
     * {@link #registerModule} are already gated by SwagAPI's session before they run,
     * so this is normally just used to personalize output (e.g. showing "Signed in as
     * X") rather than to re-check authentication.</p>
     *
     * <p>For client-side (JS) checks, plugins can instead poll the ungated JSON endpoint
     * {@code GET /swagapi/auth/status}, which returns
     * {@code {"authEnabled":bool,"authenticated":bool,"username":string|null}} and never
     * redirects — useful for a page to detect an expired session and bounce the user to
     * {@code /login?redirect=<path>} itself.</p>
     *
     * @param exchange the exchange passed into the module's {@link HttpHandler}
     * @return the signed-in username, or empty if auth is disabled or the session is invalid
     */
    Optional<String> getSessionUsername(HttpExchange exchange);
}
