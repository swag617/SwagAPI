package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IWebService;
import com.SwagDev.SwagAPI.util.ColorUtil;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class WebService implements IWebService {

    private final SwagAPI plugin;

    private HttpServer server;
    private volatile boolean running = false;
    private int port;
    private String bindAddress;
    private String publicAddress;

    private final CopyOnWriteArrayList<String> registeredModules = new CopyOnWriteArrayList<>();

    // ── Auth ──────────────────────────────────────────────────────────────────
    private boolean authEnabled;
    private long sessionDurationMs;
    private long setupTokenDurationMs;

    // ── Server-to-server ("/swagnet/") auth — separate from the human session system above ──
    private String networkSharedSecret;
    private static final String NETWORK_KEY_HEADER = "X-SwagNetwork-Key";

    /** token → session. Access is thread-safe; ConcurrentHashMap handles concurrent requests. */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    /** username (lowercase) → account. Persisted to accounts.yml. */
    private final Map<String, WebAccount> accounts = new ConcurrentHashMap<>();
    private File accountsFile;

    /** setup token → pending account-creation claim. In-memory only; single use, short-lived. */
    private final ConcurrentHashMap<String, SetupToken> setupTokens = new ConcurrentHashMap<>();

    /** reset token → pending password-reset claim. In-memory only; single use, short-lived. */
    private final ConcurrentHashMap<String, ResetToken> resetTokens = new ConcurrentHashMap<>();

    /** UUID → username, for a reset requested while the player was offline. Delivered on their next join. */
    private final ConcurrentHashMap<UUID, String> pendingPasswordResets = new ConcurrentHashMap<>();

    private record WebAccount(String username, String passwordHash, String salt, UUID linkedUuid, long created) {}
    private record SetupToken(UUID uuid, long expiry) {}
    private record ResetToken(String username, long expiry) {}
    private record Session(String username, long expiry) {}

    /** Outcome of {@link #initiatePasswordReset}, for callers (command, forgot-password form) to report back. */
    public enum ResetOutcome { DELIVERED, QUEUED, NO_LINKED_PLAYER, NOT_FOUND }

    // ─────────────────────────────────────────────────────────────────────────

    public WebService(SwagAPI plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        boolean enabled = plugin.getConfig().getBoolean("web-server.enabled", true);
        if (!enabled) {
            plugin.getLogger().info("[SwagAPI] Web server is disabled.");
            return;
        }

        port          = plugin.getConfig().getInt("web-server.port", 8080);
        bindAddress   = plugin.getConfig().getString("web-server.bind-address", "0.0.0.0");
        publicAddress = plugin.getConfig().getString("web-server.public-address", "").trim();
        int threads   = plugin.getConfig().getInt("web-server.threads", 8);

        authEnabled      = plugin.getConfig().getBoolean("web-server.auth.enabled", true);
        int sessionDays  = plugin.getConfig().getInt("web-server.auth.session-days", 30);
        sessionDurationMs = sessionDays * 24L * 60L * 60L * 1000L;
        int setupMinutes = plugin.getConfig().getInt("web-server.auth.setup-token-minutes", 15);
        setupTokenDurationMs = setupMinutes * 60L * 1000L;

        networkSharedSecret = plugin.getConfig().getString("network.shared-secret", "");

        loadAccounts();

        try {
            InetSocketAddress addr = new InetSocketAddress(bindAddress, port);
            server = HttpServer.create(addr, 0);
            server.setExecutor(Executors.newFixedThreadPool(threads));

            // ── Auth: /login ──────────────────────────────────────────────────
            if (authEnabled) {
                server.createContext("/login", exchange -> {
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleLoginPost(exchange);
                    } else {
                        handleLoginGet(exchange, false, queryParam(exchange.getRequestURI(), "redirect"));
                    }
                });

                server.createContext("/logout", exchange -> {
                    String token = getSessionToken(exchange);
                    if (token != null) sessions.remove(token);
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "swag_session=; Max-Age=0; HttpOnly; Path=/; SameSite=Lax");
                    exchange.getResponseHeaders().set("Location", "/login");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });

                server.createContext("/setup", exchange -> {
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleSetupPost(exchange);
                    } else {
                        handleSetupGet(exchange);
                    }
                });

                server.createContext("/forgot-password", exchange -> {
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleForgotPasswordPost(exchange);
                    } else {
                        handleForgotPasswordGet(exchange);
                    }
                });

                server.createContext("/reset-password", exchange -> {
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleResetPasswordPost(exchange);
                    } else {
                        handleResetPasswordGet(exchange);
                    }
                });
            }

            // ── Built-in: /swagapi/ info endpoint ────────────────────────────
            server.createContext("/swagapi/", exchange -> {
                byte[] bytes = buildInfoJson().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });

            // ── Built-in: /swagapi/modules list endpoint ──────────────────────
            server.createContext("/swagapi/modules", exchange -> {
                byte[] bytes = buildModulesJson().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });

            // ── Built-in: /swagapi/auth/status — shared login-status check for any ──
            // ── Swag plugin's frontend. Never redirects; always 200. Lets dependent ──
            // ── plugins (e.g. SwagCore) rely on SwagAPI's session instead of their ──
            // ── own login system. ──────────────────────────────────────────────
            server.createContext("/swagapi/auth/status", exchange -> {
                byte[] bytes = buildAuthStatusJson(exchange).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });

            // ── Built-in: /swagapi/shared/topbar.{css,js} — the one shared topbar ──
            // ── every dashboard (home + every registered module) includes by ──
            // ── reference, so navigation chrome never drifts between pages. ──
            server.createContext("/swagapi/shared/topbar.css", exchange -> {
                byte[] bytes = SHARED_TOPBAR_CSS.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/css; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });

            server.createContext("/swagapi/shared/topbar.js", exchange -> {
                byte[] bytes = SHARED_TOPBAR_JS.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });

            // ── Built-in: /home master dashboard (auth-gated) ────────────────
            server.createContext("/home", exchange -> {
                if (!verifySession(exchange)) return;
                byte[] bytes = buildHomePage().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });

            // ── Built-in: /account — change your own password + view all accounts ──
            server.createContext("/account", exchange -> {
                if (!verifySession(exchange)) return;
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleAccountChangePassword(exchange);
                } else {
                    byte[] bytes = buildAccountPage(exchange, null, null).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
                }
            });

            server.createContext("/account/reset", exchange -> {
                if (!verifySession(exchange)) return;
                handleAccountResetOther(exchange);
            });

            // ── Built-in: /settings — editable web-panel auth settings + read-only info ──
            server.createContext("/settings", exchange -> {
                if (!verifySession(exchange)) return;
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleSettingsPost(exchange);
                } else {
                    byte[] bytes = buildSettingsPage(null, null).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
                }
            });

            // ── Built-in: /api/status — live server stats (auth-gated) ────────
            server.createContext("/api/status", exchange -> {
                if (!verifySession(exchange)) return;
                byte[] bytes = buildStatusJson().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });

            // ── Built-in: / catch-all — redirect to /home ─────────────────────
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().set("Location", "/home");
                exchange.sendResponseHeaders(301, -1);
                exchange.close();
            });

            server.start();
            running = true;

            plugin.getLogger().info("[SwagAPI] Web server started — http://"
                    + resolveDisplayIp() + ":" + port + "/home"
                    + (authEnabled ? " (auth enabled)" : ""));

        } catch (IOException e) {
            plugin.getLogger().severe("[SwagAPI] Failed to start web server on port "
                    + port + ": " + e.getMessage());
        }
    }

    public void shutdown() {
        running = false;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        sessions.clear();
        setupTokens.clear();
        resetTokens.clear();
        pendingPasswordResets.clear();
        plugin.getLogger().info("[SwagAPI] Web server stopped.");
    }

    // ─── IWebService ─────────────────────────────────────────────────────────

    @Override
    public void registerModule(Plugin plugin, HttpHandler handler) {
        if (!running || server == null) return;

        String name    = plugin.getName().toLowerCase();
        String path    = "/swagapi/" + name + "/";
        String noSlash = "/swagapi/" + name;

        try { server.removeContext(path);    } catch (IllegalArgumentException ignored) {}
        try { server.removeContext(noSlash); } catch (IllegalArgumentException ignored) {}

        // Wrap with auth + prefix stripping
        server.createContext(path, exchange -> {
            if (!verifySession(exchange)) return;
            new PrefixStrippingHandler(path, handler).handle(exchange);
        });
        registeredModules.addIfAbsent(name);

        server.createContext(noSlash, exchange -> {
            exchange.getResponseHeaders().set("Location", path);
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });

        this.plugin.getLogger().info("[SwagAPI] Registered web module: " + name + " -> " + path);
    }

    @Override
    public void registerServiceModule(Plugin plugin, HttpHandler handler) {
        if (!running || server == null) return;

        String name    = plugin.getName().toLowerCase();
        String path    = "/swagnet/" + name + "/";
        String noSlash = "/swagnet/" + name;

        try { server.removeContext(path);    } catch (IllegalArgumentException ignored) {}
        try { server.removeContext(noSlash); } catch (IllegalArgumentException ignored) {}

        server.createContext(path, exchange -> {
            if (!verifyServiceKey(exchange)) return;
            new PrefixStrippingHandler(path, handler).handle(exchange);
        });

        server.createContext(noSlash, exchange -> {
            exchange.getResponseHeaders().set("Location", path);
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });

        this.plugin.getLogger().info("[SwagAPI] Registered network-service module: " + name + " -> " + path);
    }

    @Override
    public void unregisterModule(Plugin plugin) {
        if (!running || server == null) return;

        String name    = plugin.getName().toLowerCase();
        String path    = "/swagapi/" + name + "/";
        String noSlash = "/swagapi/" + name;

        try { server.removeContext(path);    } catch (IllegalArgumentException ignored) {}
        try { server.removeContext(noSlash); } catch (IllegalArgumentException ignored) {}

        registeredModules.remove(name);
        this.plugin.getLogger().info("[SwagAPI] Unregistered web module: " + name);
    }

    @Override
    public void unregisterServiceModule(Plugin plugin) {
        if (!running || server == null) return;

        String name    = plugin.getName().toLowerCase();
        String path    = "/swagnet/" + name + "/";
        String noSlash = "/swagnet/" + name;

        try { server.removeContext(path);    } catch (IllegalArgumentException ignored) {}
        try { server.removeContext(noSlash); } catch (IllegalArgumentException ignored) {}

        this.plugin.getLogger().info("[SwagAPI] Unregistered network-service module: " + name);
    }

    /**
     * Gate for every {@code /swagnet/} route — a shared-secret header check, not a session
     * cookie. Fails closed: if {@code network.shared-secret} isn't configured, every request
     * is rejected (503) rather than silently allowed through unauthenticated.
     */
    private boolean verifyServiceKey(HttpExchange exchange) throws IOException {
        if (networkSharedSecret == null || networkSharedSecret.isBlank()) {
            byte[] body = "{\"error\":\"network.shared-secret is not configured on this server\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            return false;
        }

        String provided = exchange.getRequestHeaders().getFirst(NETWORK_KEY_HEADER);
        boolean valid = provided != null && MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                networkSharedSecret.getBytes(StandardCharsets.UTF_8));
        if (valid) return true;

        byte[] body = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(401, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        return false;
    }

    @Override public boolean isRunning()                 { return running; }
    @Override public int getPort()                       { return port; }
    @Override public String getBindAddress()             { return bindAddress; }
    @Override public List<String> getRegisteredModules() { return Collections.unmodifiableList(registeredModules); }

    @Override
    public String getPluginUrl(String moduleName) {
        return "http://" + resolveDisplayIp() + ":" + port
                + "/swagapi/" + moduleName.toLowerCase() + "/";
    }

    /** Full browser URL to the master home dashboard. */
    public String getHomeUrl() {
        return "http://" + resolveDisplayIp() + ":" + port + "/home";
    }

    // ─── Accounts ────────────────────────────────────────────────────────────

    public boolean isAuthEnabled() { return authEnabled; }

    public boolean hasAnyAccount() { return !accounts.isEmpty(); }

    public boolean hasAccountLinkedTo(UUID uuid) {
        if (uuid == null) return false;
        for (WebAccount acc : accounts.values()) {
            if (uuid.equals(acc.linkedUuid())) return true;
        }
        return false;
    }

    /** Returns the panel account username(s) linked to the given player. Usually 0 or 1; backs /swagapi web whoami. */
    public List<String> getAccountUsernamesLinkedTo(UUID uuid) {
        List<String> result = new ArrayList<>();
        if (uuid == null) return result;
        for (WebAccount acc : accounts.values()) {
            if (uuid.equals(acc.linkedUuid())) result.add(acc.username());
        }
        return result;
    }

    /** All web panel account usernames. Used for tab-completing /swagapi web resetpassword. */
    public List<String> getAllAccountUsernames() {
        List<String> result = new ArrayList<>();
        for (WebAccount acc : accounts.values()) result.add(acc.username());
        return result;
    }

    /** Read-only summary of an account, for the /account page's admin table. */
    public record AccountSummary(String username, String linkedPlayerName, long created) {}

    /** All accounts, resolved to display-friendly form, sorted by username. Backs the /account page. */
    public List<AccountSummary> getAccountSummaries() {
        List<AccountSummary> result = new ArrayList<>();
        for (WebAccount acc : accounts.values()) {
            String playerName = acc.linkedUuid() != null ? Bukkit.getOfflinePlayer(acc.linkedUuid()).getName() : null;
            result.add(new AccountSummary(acc.username(), playerName, acc.created()));
        }
        result.sort((a, b) -> a.username().compareToIgnoreCase(b.username()));
        return result;
    }

    /**
     * Starts a password reset for the given account. If the account's linked player is
     * currently online, delivers a clickable reset link to them immediately via chat.
     * If offline, queues the reset for delivery the next time they join (see
     * {@link #deliverPendingResetIfAny}). Backs both {@code /swagapi web resetpassword}
     * and the web panel's "Forgot password?" flow.
     */
    public ResetOutcome initiatePasswordReset(String username) {
        WebAccount acc = accounts.get(username.toLowerCase());
        if (acc == null) return ResetOutcome.NOT_FOUND;
        UUID uuid = acc.linkedUuid();
        if (uuid == null) return ResetOutcome.NO_LINKED_PLAYER;

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            sendResetLink(player, acc.username());
            return ResetOutcome.DELIVERED;
        }
        pendingPasswordResets.put(uuid, acc.username());
        return ResetOutcome.QUEUED;
    }

    /** Called on player join — delivers a queued password reset link, if one is pending for them. */
    public void deliverPendingResetIfAny(Player player) {
        String username = pendingPasswordResets.remove(player.getUniqueId());
        if (username != null) sendResetLink(player, username);
    }

    private void sendResetLink(Player player, String username) {
        String url = createPasswordResetUrl(username);
        Component link = Component.text("[Click here to reset your password]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Opens in your browser", NamedTextColor.GRAY)));
        Component msg = ColorUtil.toComponent("&8[&bSwagAPI&8] &7Password reset requested for your account. ")
                .append(link);
        player.sendMessage(msg);
    }

    /**
     * Generates a one-time, short-lived setup link for the given player to create
     * their own web panel account. The token is bound to the player's UUID so the
     * created account is automatically linked to them.
     */
    public String createAccountSetupUrl(UUID uuid) {
        String token = generateToken();
        setupTokens.put(token, new SetupToken(uuid, System.currentTimeMillis() + setupTokenDurationMs));
        return "http://" + resolveDisplayIp() + ":" + port + "/setup?token=" + token;
    }

    private UUID resolveSetupToken(String token) {
        if (token == null) return null;
        SetupToken st = setupTokens.get(token);
        if (st == null) return null;
        if (System.currentTimeMillis() > st.expiry()) {
            setupTokens.remove(token);
            return null;
        }
        return st.uuid();
    }

    /**
     * Generates a one-time, short-lived link to reset the given account's password.
     * Unlike a setup token (bound to a player UUID, for creating a new account), this
     * is bound to an existing username.
     */
    public String createPasswordResetUrl(String username) {
        String token = generateToken();
        resetTokens.put(token, new ResetToken(username, System.currentTimeMillis() + setupTokenDurationMs));
        return "http://" + resolveDisplayIp() + ":" + port + "/reset-password?token=" + token;
    }

    private String resolveResetToken(String token) {
        if (token == null) return null;
        ResetToken rt = resetTokens.get(token);
        if (rt == null) return null;
        if (System.currentTimeMillis() > rt.expiry()) {
            resetTokens.remove(token);
            return null;
        }
        return rt.username();
    }

    private synchronized void createAccount(String username, String password, UUID linkedUuid) {
        byte[] saltBytes = new byte[16];
        secureRandom.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        String hash = hashPassword(password, saltBytes);
        accounts.put(username.toLowerCase(), new WebAccount(username, hash, salt, linkedUuid, System.currentTimeMillis()));
        saveAccounts();
    }

    /** Updates an existing account's password, keeping its username/linked-player/created-date unchanged. */
    private synchronized void resetPassword(String username, String newPassword) {
        WebAccount existing = accounts.get(username.toLowerCase());
        if (existing == null) return;
        byte[] saltBytes = new byte[16];
        secureRandom.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        String hash = hashPassword(newPassword, saltBytes);
        accounts.put(username.toLowerCase(),
                new WebAccount(existing.username(), hash, salt, existing.linkedUuid(), existing.created()));
        saveAccounts();
    }

    private boolean verifyLogin(String username, String password) {
        WebAccount acc = accounts.get(username.toLowerCase());
        if (acc == null) return false;
        byte[] saltBytes = Base64.getDecoder().decode(acc.salt());
        String computed = hashPassword(password, saltBytes);
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                acc.passwordHash().getBytes(StandardCharsets.UTF_8));
    }

    private static String hashPassword(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return Base64.getEncoder().encodeToString(skf.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    private void loadAccounts() {
        accounts.clear();
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        accountsFile = new File(plugin.getDataFolder(), "accounts.yml");
        if (!accountsFile.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(accountsFile);
        ConfigurationSection section = yaml.getConfigurationSection("accounts");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection acc = section.getConfigurationSection(key);
            if (acc == null) continue;
            String hash = acc.getString("password-hash");
            String salt = acc.getString("salt");
            if (hash == null || salt == null) continue;
            String uuidStr = acc.getString("uuid");
            UUID uuid = uuidStr != null ? UUID.fromString(uuidStr) : null;
            long created = acc.getLong("created", System.currentTimeMillis());
            accounts.put(key.toLowerCase(), new WebAccount(key, hash, salt, uuid, created));
        }
    }

    private void saveAccounts() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (WebAccount acc : accounts.values()) {
            String base = "accounts." + acc.username();
            yaml.set(base + ".password-hash", acc.passwordHash());
            yaml.set(base + ".salt", acc.salt());
            yaml.set(base + ".uuid", acc.linkedUuid() != null ? acc.linkedUuid().toString() : null);
            yaml.set(base + ".created", acc.created());
        }
        try {
            yaml.save(accountsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[SwagAPI] Failed to save accounts.yml: " + e.getMessage());
        }
    }

    // ─── Session / auth helpers ───────────────────────────────────────────────

    /**
     * Returns true if the request carries a valid session.
     * If auth is disabled, always returns true.
     * If the session is valid, slides the expiry window.
     * If invalid, sends a 302 to /login (preserving the original path as ?redirect=,
     * or 401 for JSON callers) and returns false.
     */
    private boolean verifySession(HttpExchange exchange) throws IOException {
        if (!authEnabled) return true;
        if (lookupSession(exchange, true) != null) return true;

        // No valid session — decide how to reject
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept != null && accept.contains("application/json")) {
            byte[] body = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        } else {
            URI uri = exchange.getRequestURI();
            String target = uri.getRawQuery() != null ? uri.getRawPath() + "?" + uri.getRawQuery() : uri.getRawPath();
            exchange.getResponseHeaders().set("Location",
                    "/login?redirect=" + URLEncoder.encode(target, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }
        return false;
    }

    /**
     * Resolves the caller's session, if any. When {@code refresh} is true and the
     * session is valid, slides the expiry window and re-sends the cookie — used both
     * by {@link #verifySession} and the public auth-status check, since any legitimate
     * "am I logged in" poll should count as activity.
     */
    private Session lookupSession(HttpExchange exchange, boolean refresh) {
        String token = getSessionToken(exchange);
        if (token == null) return null;

        Session session = sessions.get(token);
        if (session == null) return null;
        if (System.currentTimeMillis() > session.expiry()) {
            sessions.remove(token);
            return null;
        }
        if (!refresh) return session;

        Session renewed = new Session(session.username(), System.currentTimeMillis() + sessionDurationMs);
        sessions.put(token, renewed);
        exchange.getResponseHeaders().add("Set-Cookie",
                "swag_session=" + token
                + "; Max-Age=" + (sessionDurationMs / 1000)
                + "; HttpOnly; Path=/; SameSite=Lax");
        return renewed;
    }

    /**
     * Server-side identity check for dependent plugins. Since module routes registered
     * via {@link #registerModule} are already gated by {@link #verifySession} before the
     * handler runs, this simply reports who is signed in — plugins like SwagCore should
     * use this (or {@code /swagapi/auth/status} client-side) instead of implementing
     * their own login system.
     */
    @Override
    public Optional<String> getSessionUsername(HttpExchange exchange) {
        if (!authEnabled) return Optional.empty();
        Session session = lookupSession(exchange, false);
        return session != null ? Optional.of(session.username()) : Optional.empty();
    }

    private String buildAuthStatusJson(HttpExchange exchange) {
        if (!authEnabled) {
            return "{\"authEnabled\":false,\"authenticated\":true,\"username\":null}";
        }
        Session session = lookupSession(exchange, true);
        if (session == null) {
            return "{\"authEnabled\":true,\"authenticated\":false,\"username\":null}";
        }
        return "{\"authEnabled\":true,\"authenticated\":true,\"username\":\""
                + escapeJson(session.username()) + "\"}";
    }

    private String getSessionToken(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("swag_session=")) {
                return trimmed.substring("swag_session=".length()).trim();
            }
        }
        return null;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String queryParam(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && URLDecoder.decode(kv[0], StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void handleLoginGet(HttpExchange exchange, boolean showError, String redirect) throws IOException {
        byte[] bytes = buildLoginPage(showError, redirect).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private void handleLoginPost(HttpExchange exchange) throws IOException {
        // Parse application/x-www-form-urlencoded body
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        String username = "";
        String password = "";
        String redirect = null;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String val = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            switch (key) {
                case "username" -> username = val;
                case "password" -> password = val;
                case "redirect" -> redirect = val;
            }
        }

        if (verifyLogin(username, password)) {
            WebAccount acc = accounts.get(username.toLowerCase());
            String token = generateToken();
            sessions.put(token, new Session(acc.username(), System.currentTimeMillis() + sessionDurationMs));
            exchange.getResponseHeaders().add("Set-Cookie",
                    "swag_session=" + token
                    + "; Max-Age=" + (sessionDurationMs / 1000)
                    + "; HttpOnly; Path=/; SameSite=Lax");
            exchange.getResponseHeaders().set("Location", sanitizeRedirect(redirect));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            plugin.getLogger().info("[SwagAPI] Web panel login: " + username
                    + " from " + exchange.getRemoteAddress());
        } else {
            plugin.getLogger().warning("[SwagAPI] Failed web panel login for '"
                    + username + "' from " + exchange.getRemoteAddress());
            handleLoginGet(exchange, true, redirect);
        }
    }

    /** Only allows same-origin relative paths, to prevent an open redirect via ?redirect=. */
    private static String sanitizeRedirect(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/") || path.startsWith("//")) return "/home";
        return path;
    }

    private void handleSetupGet(HttpExchange exchange) throws IOException {
        String token = queryParam(exchange.getRequestURI(), "token");
        UUID owner = resolveSetupToken(token);
        byte[] bytes = buildSetupPage(token, owner != null, null).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private void handleSetupPost(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        String token = "", username = "", password = "", confirm = "";
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String val = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            switch (key) {
                case "token"    -> token = val;
                case "username" -> username = val;
                case "password" -> password = val;
                case "confirm"  -> confirm = val;
            }
        }

        UUID owner = resolveSetupToken(token);
        String error = null;
        if (owner == null) {
            error = "This setup link is invalid or has expired.";
        } else if (!username.matches("[A-Za-z0-9_]{3,16}")) {
            error = "Username must be 3-16 characters (letters, numbers, underscore).";
        } else if (accounts.containsKey(username.toLowerCase())) {
            error = "That username is already taken.";
        } else if (password.length() < 8) {
            error = "Password must be at least 8 characters.";
        } else if (!password.equals(confirm)) {
            error = "Passwords do not match.";
        }

        if (error != null) {
            byte[] bytes = buildSetupPage(token, owner != null, error).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            return;
        }

        createAccount(username, password, owner);
        setupTokens.remove(token);

        String sessionToken = generateToken();
        sessions.put(sessionToken, new Session(username, System.currentTimeMillis() + sessionDurationMs));
        exchange.getResponseHeaders().add("Set-Cookie",
                "swag_session=" + sessionToken
                + "; Max-Age=" + (sessionDurationMs / 1000)
                + "; HttpOnly; Path=/; SameSite=Lax");
        exchange.getResponseHeaders().set("Location", "/home");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();

        plugin.getLogger().info("[SwagAPI] Web panel account created: " + username
                + " (linked to " + owner + ")");
    }

    private void handleForgotPasswordGet(HttpExchange exchange) throws IOException {
        byte[] bytes = buildForgotPasswordPage(false).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private void handleForgotPasswordPost(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        String username = "";
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String val = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            if ("username".equals(key)) username = val;
        }

        // Always show the same confirmation regardless of outcome, so the form can't be
        // used to enumerate which usernames exist.
        ResetOutcome outcome = initiatePasswordReset(username.trim());
        plugin.getLogger().info("[SwagAPI] Password reset requested for '" + username
                + "' from " + exchange.getRemoteAddress() + " -> " + outcome);

        byte[] bytes = buildForgotPasswordPage(true).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private void handleResetPasswordGet(HttpExchange exchange) throws IOException {
        String token = queryParam(exchange.getRequestURI(), "token");
        String username = resolveResetToken(token);
        byte[] bytes = buildResetPasswordPage(token, username, null).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private void handleResetPasswordPost(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        String token = "", password = "", confirm = "";
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String val = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            switch (key) {
                case "token"    -> token = val;
                case "password" -> password = val;
                case "confirm"  -> confirm = val;
            }
        }

        String username = resolveResetToken(token);
        String error = null;
        if (username == null) {
            error = "This reset link is invalid or has expired.";
        } else if (password.length() < 8) {
            error = "Password must be at least 8 characters.";
        } else if (!password.equals(confirm)) {
            error = "Passwords do not match.";
        }

        if (error != null) {
            byte[] bytes = buildResetPasswordPage(token, username, error).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            return;
        }

        resetPassword(username, password);
        resetTokens.remove(token);

        String sessionToken = generateToken();
        sessions.put(sessionToken, new Session(username, System.currentTimeMillis() + sessionDurationMs));
        exchange.getResponseHeaders().add("Set-Cookie",
                "swag_session=" + sessionToken
                + "; Max-Age=" + (sessionDurationMs / 1000)
                + "; HttpOnly; Path=/; SameSite=Lax");
        exchange.getResponseHeaders().set("Location", "/home");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();

        plugin.getLogger().info("[SwagAPI] Password reset completed for account: " + username);
    }

    private void handleAccountChangePassword(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        String current = "", newPass = "", confirm = "";
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String val = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            switch (key) {
                case "current"  -> current = val;
                case "password" -> newPass = val;
                case "confirm"  -> confirm = val;
            }
        }

        Session session = lookupSession(exchange, false);
        String error = null, success = null;
        if (session == null) {
            error = "Your session has expired — please log in again.";
        } else if (!verifyLogin(session.username(), current)) {
            error = "Current password is incorrect.";
        } else if (newPass.length() < 8) {
            error = "New password must be at least 8 characters.";
        } else if (!newPass.equals(confirm)) {
            error = "New passwords do not match.";
        } else {
            resetPassword(session.username(), newPass);
            success = "Password changed.";
            plugin.getLogger().info("[SwagAPI] " + session.username() + " changed their own password.");
        }

        byte[] bytes = buildAccountPage(exchange, error, success).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private void handleAccountResetOther(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        String username = "";
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && "username".equals(URLDecoder.decode(kv[0], StandardCharsets.UTF_8))) {
                username = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }

        ResetOutcome outcome = initiatePasswordReset(username);
        String msg = switch (outcome) {
            case DELIVERED -> "Reset link sent to " + username + " in-game.";
            case QUEUED -> username + " is offline — the link will be delivered next time they join.";
            case NO_LINKED_PLAYER -> "That account has no linked player to deliver a link to.";
            case NOT_FOUND -> "No account named '" + username + "'.";
        };
        plugin.getLogger().info("[SwagAPI] Password reset for '" + username + "' triggered from /account -> " + outcome);

        byte[] bytes = buildAccountPage(exchange, null, msg).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static final String[] WEB_AUTH_SECTION = {"web-server", "auth"};

    private void handleSettingsPost(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        Map<String, String> form = new java.util.LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }

        String error = null, success = null;
        try {
            int sessionDays = Integer.parseInt(form.getOrDefault("session-days", "").trim());
            int setupMinutes = Integer.parseInt(form.getOrDefault("setup-token-minutes", "").trim());
            boolean remindOnJoin = "true".equalsIgnoreCase(form.getOrDefault("remind-ops-on-join", "false"));
            boolean authOn = "true".equalsIgnoreCase(form.getOrDefault("auth-enabled", "false"));

            if (sessionDays < 1 || setupMinutes < 1) {
                error = "Session days and setup-link minutes must be at least 1.";
            } else {
                String text = readConfigFile();
                text = setConfigScalar(text, WEB_AUTH_SECTION, "session-days", String.valueOf(sessionDays));
                text = setConfigScalar(text, WEB_AUTH_SECTION, "setup-token-minutes", String.valueOf(setupMinutes));
                text = setConfigScalar(text, WEB_AUTH_SECTION, "remind-ops-on-join", String.valueOf(remindOnJoin));
                text = setConfigScalar(text, WEB_AUTH_SECTION, "enabled", String.valueOf(authOn));
                writeConfigFile(text);

                plugin.reloadConfig();
                authEnabled = plugin.getConfig().getBoolean("web-server.auth.enabled", true);
                sessionDurationMs = sessionDays * 24L * 60L * 60L * 1000L;
                setupTokenDurationMs = setupMinutes * 60L * 1000L;

                success = "Settings saved.";
                plugin.getLogger().info("[SwagAPI] Web panel settings updated via /settings.");
            }
        } catch (NumberFormatException e) {
            error = "Session days and setup-link minutes must be whole numbers.";
        }

        byte[] bytes = buildSettingsPage(error, success).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    // ─── JSON builders ───────────────────────────────────────────────────────

    private String buildInfoJson() {
        return "{"
                + "\"status\":\"ok\","
                + "\"plugin\":\"SwagAPI\","
                + "\"version\":\"" + escapeJson(plugin.getPluginMeta().getVersion()) + "\","
                + "\"port\":" + port + ","
                + "\"modules\":" + registeredModules.size()
                + "}";
    }

    private String buildModulesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String mod : registeredModules) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(mod)).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private String buildStatusJson() {
        Runtime rt    = Runtime.getRuntime();
        long usedMb   = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb    = rt.maxMemory() / (1024L * 1024L);
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        double[] tps  = Bukkit.getServer().getTPS();
        double tps1m  = Math.min(tps[0], 20.0);
        double tps5m  = Math.min(tps[1], 20.0);
        double tps15m = Math.min(tps[2], 20.0);

        int online     = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        String version = Bukkit.getVersion();
        String motd    = Bukkit.getServer().getMotd();

        StringBuilder pluginsArr = new StringBuilder("[");
        boolean first = true;
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (!first) pluginsArr.append(",");
            pluginsArr.append("{\"name\":\"").append(escapeJson(p.getName()))
                      .append("\",\"version\":\"").append(escapeJson(p.getPluginMeta().getVersion()))
                      .append("\",\"enabled\":").append(p.isEnabled()).append("}");
            first = false;
        }
        pluginsArr.append("]");

        StringBuilder worldsArr = new StringBuilder("[");
        first = true;
        for (World w : Bukkit.getWorlds()) {
            if (!first) worldsArr.append(",");
            worldsArr.append("{\"name\":\"").append(escapeJson(w.getName()))
                     .append("\",\"env\":\"").append(escapeJson(w.getEnvironment().name()))
                     .append("\",\"chunks\":").append(w.getLoadedChunks().length)
                     .append("}");
            first = false;
        }
        worldsArr.append("]");

        return "{"
            + "\"players\":{\"online\":" + online + ",\"max\":" + maxPlayers + "},"
            + "\"tps\":{\"1m\":" + String.format("%.1f", tps1m)
                + ",\"5m\":" + String.format("%.1f", tps5m)
                + ",\"15m\":" + String.format("%.1f", tps15m) + "},"
            + "\"memory\":{\"used\":" + usedMb + ",\"max\":" + maxMb + "},"
            + "\"uptime\":" + uptimeMs + ","
            + "\"version\":\"" + escapeJson(version) + "\","
            + "\"motd\":\"" + escapeJson(motd) + "\","
            + "\"port\":" + port + ","
            + "\"sessions\":" + sessions.size() + ","
            + "\"modules\":" + buildModulesJson() + ","
            + "\"plugins\":" + pluginsArr + ","
            + "\"worlds\":" + worldsArr
            + "}";
    }

    // ─── Shared topbar (served to every dashboard, including this plugin's own /home) ──

    /**
     * Self-contained — declares its own {@code :root} palette rather than depending on
     * variables the host page happens to define, so it renders identically on every
     * dashboard regardless of that page's own (possibly completely different) theme.
     */
    private static final String SHARED_TOPBAR_CSS = """
            :root{
              --st-bg-sidebar:#1e1e27;--st-border-dim:#24242e;--st-border-accent:rgba(118,75,162,.4);
              --st-blue:#667eea;--st-purple:#764ba2;
              --st-grad:linear-gradient(135deg,#667eea 0%,#764ba2 100%);
              --st-text:#e4e4ea;--st-text-muted:#8b8b96;
              --st-danger:#ec5f5f;--st-chip-bg:#24242f;
              --st-font:'Inter','Segoe UI',system-ui,sans-serif;--st-mono:'JetBrains Mono',monospace;
            }
            .st-bar{
              display:flex;align-items:center;gap:1rem;flex-wrap:wrap;
              padding:.75rem 1.5rem;background:var(--st-bg-sidebar);
              border-bottom:1px solid var(--st-border-dim);
              position:sticky;top:0;z-index:1000;
              font-family:var(--st-font);color:var(--st-text);box-sizing:border-box;
            }
            .st-bar *{box-sizing:border-box}
            .st-left{display:flex;align-items:center;gap:.75rem}
            .st-back{
              display:inline-flex;align-items:center;justify-content:center;
              width:28px;height:28px;border-radius:8px;flex-shrink:0;
              background:var(--st-chip-bg);border:1px solid var(--st-border-dim);
              color:var(--st-text-muted);text-decoration:none;font-size:1.05rem;line-height:1;
              transition:color .15s,border-color .15s;
            }
            .st-back:hover{color:var(--st-blue);border-color:var(--st-border-accent)}
            .st-brand{display:flex;align-items:center;gap:.5rem;font-weight:700;font-size:1rem;letter-spacing:-.01em;white-space:nowrap}
            .st-dot{width:9px;height:9px;border-radius:50%;background:var(--st-grad);flex-shrink:0;box-shadow:0 0 10px rgba(102,126,234,.6)}
            .st-sub{color:var(--st-text-muted);font-weight:500;font-size:.8rem;margin-left:.15rem}
            .st-chips{display:flex;gap:.5rem;flex-wrap:wrap;flex:1}
            .st-chip{
              background:var(--st-chip-bg);border:1px solid var(--st-border-dim);border-radius:20px;
              font-family:var(--st-mono);padding:.3rem .8rem;font-size:.76rem;
              color:var(--st-text-muted);white-space:nowrap;letter-spacing:.01em;
            }
            .st-whoami{color:var(--st-text-muted);font-family:var(--st-mono);font-size:.78rem;white-space:nowrap}
            .st-logout{
              font-family:var(--st-font);font-weight:600;font-size:.78rem;padding:.4rem .85rem;border-radius:8px;
              background:rgba(236,95,95,.12);border:1px solid rgba(236,95,95,.3);
              color:var(--st-danger);text-decoration:none;white-space:nowrap;transition:background .15s;
            }
            .st-logout:hover{background:rgba(236,95,95,.2)}
            """;

    /**
     * Self-inserting: pages that include this script don't need any placeholder markup —
     * it builds the topbar and prepends it to {@code <body>} on load. Set
     * {@code window.SWAG_TOPBAR = {plugin:"SwagCore"}} before the include to get a back
     * arrow to /home and a plugin-specific brand label; omit it for "home mode" (no back
     * arrow, brand reads "SwagAPI"). Polls the same {@code /api/status} every 30s that
     * /home itself uses, and re-broadcasts the parsed JSON as a {@code swag-status}
     * CustomEvent on {@code window} so host pages can reuse the same fetch for their own
     * content instead of polling separately.
     */
    private static final String SHARED_TOPBAR_JS = """
            (function(){
              var cfg = window.SWAG_TOPBAR || {};
              var pluginName = cfg.plugin || null;

              function esc(s){
                return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
              }

              var bar = document.createElement('header');
              bar.className = 'st-bar';
              var backHtml = pluginName
                ? '<a class="st-back" href="/home" title="Back to Dashboard">&larr;</a>'
                : '';
              var brandLabel = pluginName ? esc(pluginName) : 'SwagAPI';
              var brandSub = pluginName ? '' : '<span class="st-sub">Control Panel</span>';
              bar.innerHTML =
                '<div class="st-left">' + backHtml +
                  '<div class="st-brand"><span class="st-dot"></span>' + brandLabel + brandSub + '</div>' +
                '</div>' +
                '<div class="st-chips">' +
                  '<span class="st-chip" id="st-chip-players">Players: --</span>' +
                  '<span class="st-chip" id="st-chip-tps">TPS: --</span>' +
                  '<span class="st-chip" id="st-chip-mem">Mem: --</span>' +
                  '<span class="st-chip" id="st-chip-uptime">Uptime: --</span>' +
                '</div>' +
                '<span class="st-whoami" id="st-whoami"></span>' +
                '<a href="/logout" class="st-logout">Logout</a>';

              function mount(){ document.body.insertBefore(bar, document.body.firstChild); }
              if (document.body) mount();
              else document.addEventListener('DOMContentLoaded', mount);

              function tpsColor(t){ return t>=18?'#5fe05f':t>=15?'#f0c060':'#ec5f5f'; }
              function fmtUp(ms){
                var h=Math.floor(ms/3600000),m=Math.floor((ms%3600000)/60000),s=Math.floor((ms%60000)/1000);
                return h>0?h+'h '+m+'m':m>0?m+'m '+s+'s':s+'s';
              }

              function refresh(){
                fetch('/api/status',{credentials:'include'}).then(function(r){
                  if (r.status===401||r.status===302){
                    window.location='/login?redirect='+encodeURIComponent(window.location.pathname+window.location.search);
                    return null;
                  }
                  return r.ok ? r.json() : null;
                }).then(function(d){
                  if (!d) return;
                  document.getElementById('st-chip-players').textContent='Players: '+d.players.online+'/'+d.players.max;
                  var t1=parseFloat(d.tps['1m']);
                  var tpsEl=document.getElementById('st-chip-tps');
                  tpsEl.textContent='TPS: '+t1.toFixed(1);
                  tpsEl.style.color=tpsColor(t1);
                  document.getElementById('st-chip-mem').textContent='Mem: '+(d.memory.used/1024).toFixed(1)+'/'+(d.memory.max/1024).toFixed(1)+'G';
                  document.getElementById('st-chip-uptime').textContent='Uptime: '+fmtUp(d.uptime);
                  window.dispatchEvent(new CustomEvent('swag-status', {detail: d}));
                }).catch(function(){});
              }

              function loadWhoAmI(){
                fetch('/swagapi/auth/status',{credentials:'include'}).then(function(r){return r.json();}).then(function(d){
                  var el=document.getElementById('st-whoami');
                  if (el) el.textContent = d.username ? ('Signed in as '+d.username) : '';
                }).catch(function(){});
              }

              refresh();
              loadWhoAmI();
              setInterval(refresh, 30000);
            })();
            """;

    // ─── HTML pages ──────────────────────────────────────────────────────────

    /** Shared head/style block for the login and account-setup pages. */
    private static final String AUTH_PAGE_STYLE = """
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
            <style>
            *,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
            :root{
              --bg:#16161c;--bg-card:#1c1c24;--border:#2a2a35;--border-dim:#24242e;
              --blue:#667eea;--purple:#764ba2;--purple-muted:#9b8ec4;
              --grad:linear-gradient(135deg,#667eea 0%,#764ba2 100%);
              --text:#e4e4ea;--text-muted:#8b8b96;--text-dim:#5c5c68;
              --danger:#ec5f5f;--font:'Inter','Segoe UI',system-ui,sans-serif;--mono:'JetBrains Mono',monospace;
            }
            body{background:var(--bg);color:var(--text);font-family:var(--font);font-size:14px;line-height:1.5}
            .overlay{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:var(--bg);overflow:hidden}
            .login-bg{position:absolute;inset:0;background:radial-gradient(ellipse 55% 45% at 18% 20%,rgba(102,126,234,.16) 0%,transparent 70%),radial-gradient(ellipse 55% 45% at 85% 82%,rgba(118,75,162,.16) 0%,transparent 70%);pointer-events:none}
            .login-card{position:relative;background:var(--bg-card);border:1px solid var(--border);border-radius:14px;padding:2.6rem 2.4rem;width:100%;max-width:360px;margin:1rem;text-align:center;box-shadow:0 20px 60px rgba(0,0,0,.45)}
            .brand-mark{display:inline-flex;align-items:center;justify-content:center;font-family:var(--mono);font-size:.72rem;font-weight:600;letter-spacing:.14em;text-transform:uppercase;color:var(--purple-muted);margin-bottom:1.1rem}
            .brand-dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:var(--grad);margin-right:.5rem;box-shadow:0 0 10px rgba(102,126,234,.6)}
            .login-card h1{font-size:1.6rem;font-weight:700;margin-bottom:.35rem;letter-spacing:-.01em}
            .subtitle{color:var(--text-muted);margin-bottom:1.6rem;font-size:.88rem}
            .form-group{margin-bottom:1.1rem;text-align:left}
            .form-group input{width:100%;padding:.65rem .85rem;background:var(--bg);border:1px solid var(--border);border-radius:8px;color:var(--text);font-size:.95rem;font-family:var(--font);outline:none;transition:border-color .15s,box-shadow .15s}
            .form-group input:focus{border-color:var(--purple);box-shadow:0 0 0 3px rgba(118,75,162,.15)}
            .btn{display:inline-block;padding:.6rem 1.3rem;border:none;border-radius:8px;cursor:pointer;font-family:var(--font);font-size:.88rem;font-weight:600;letter-spacing:.01em;transition:transform .15s,box-shadow .15s}
            .btn-primary{background:var(--grad);color:#fff;width:100%}
            .btn-primary:hover{box-shadow:0 6px 18px rgba(102,126,234,.35);transform:translateY(-1px)}
            .error-msg{color:var(--danger);margin-top:.7rem;font-size:.82rem;min-height:1.2em}
            .hint{color:var(--text-dim);font-size:.76rem;margin-top:.9rem;line-height:1.5}
            .hint code{font-family:var(--mono);color:var(--purple-muted);background:var(--bg);padding:.1rem .35rem;border-radius:4px}
            .brand-wordmark{font-family:var(--mono);font-size:.78rem;font-weight:600;letter-spacing:.06em;background:var(--grad);-webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;text-decoration:none;display:inline-block;margin-top:1.6rem;opacity:.85}
            </style>
            """;

    private String buildLoginPage(boolean showError, String redirect) {
        String errorBlock = showError ? "Invalid username or password." : "";
        String hintBlock = accounts.isEmpty()
                ? "No accounts exist yet. Ask an operator to join the server, or run <code>/swagapi web setup</code> in-game to get a setup link."
                : "";
        String redirectValue = escapeHtmlAttr(redirect == null ? "" : redirect);
        return ("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>SwagAPI — Login</title>
                """ + AUTH_PAGE_STYLE + """
                </head>
                <body>
                <div class="overlay">
                  <div class="login-bg"></div>
                  <div class="login-card">
                    <div class="brand-mark"><span class="brand-dot"></span>SwagAPI</div>
                    <h1>Control Panel</h1>
                    <p class="subtitle">Enter your account credentials to continue</p>
                    <form method="POST" action="/login">
                      <input type="hidden" name="redirect" value="{{REDIRECT}}">
                      <div class="form-group">
                        <input name="username" type="text" placeholder="Username" autocomplete="username" autofocus required>
                      </div>
                      <div class="form-group">
                        <input name="password" type="password" placeholder="Password" autocomplete="current-password" required>
                      </div>
                      <button type="submit" class="btn btn-primary">Sign In</button>
                    </form>
                    <p class="error-msg">{{ERROR}}</p>
                    <p class="hint"><a href="/forgot-password" style="color:var(--purple-muted)">Forgot password?</a></p>
                    <p class="hint">{{HINT}}</p>
                    <a class="brand-wordmark" href="https://swag617.github.io/" target="_blank" rel="noopener">swag617</a>
                  </div>
                </div>
                </body>
                </html>
                """).replace("{{ERROR}}", errorBlock).replace("{{HINT}}", hintBlock).replace("{{REDIRECT}}", redirectValue);
    }

    private String buildSetupPage(String token, boolean tokenValid, String error) {
        String tokenValue = escapeHtmlAttr(token == null ? "" : token);
        String errorBlock = error != null ? error : "";

        String body = tokenValid
                ? ("""
                    <h1>Create Account</h1>
                    <p class="subtitle">Set up your SwagAPI panel login</p>
                    <form method="POST" action="/setup">
                      <input type="hidden" name="token" value="{{TOKEN}}">
                      <div class="form-group">
                        <input name="username" type="text" placeholder="Username" autocomplete="username" autofocus required>
                      </div>
                      <div class="form-group">
                        <input name="password" type="password" placeholder="Password (min 8 characters)" autocomplete="new-password" required>
                      </div>
                      <div class="form-group">
                        <input name="confirm" type="password" placeholder="Confirm password" autocomplete="new-password" required>
                      </div>
                      <button type="submit" class="btn btn-primary">Create Account</button>
                    </form>
                    <p class="error-msg">{{ERROR}}</p>
                    """).replace("{{TOKEN}}", tokenValue).replace("{{ERROR}}", errorBlock)
                : """
                    <h1>Link Invalid</h1>
                    <p class="subtitle">This setup link is invalid or has expired.</p>
                    <p class="hint">Ask an operator to rejoin the server, or run <code>/swagapi web setup</code> in-game to request a new link.</p>
                    """;

        return ("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>SwagAPI — Create Account</title>
                """ + AUTH_PAGE_STYLE + """
                </head>
                <body>
                <div class="overlay">
                  <div class="login-bg"></div>
                  <div class="login-card">
                    <div class="brand-mark"><span class="brand-dot"></span>SwagAPI</div>
                    {{BODY}}
                    <a class="brand-wordmark" href="https://swag617.github.io/" target="_blank" rel="noopener">swag617</a>
                  </div>
                </div>
                </body>
                </html>
                """).replace("{{BODY}}", body);
    }

    private String buildForgotPasswordPage(boolean submitted) {
        String body = submitted
                ? """
                    <h1>Check In-Game Chat</h1>
                    <p class="subtitle">If that account exists, a reset link has been sent &mdash;
                    immediately if the linked player is online, otherwise the next time they join.</p>
                    <a class="btn btn-primary" href="/login" style="display:block;text-align:center;text-decoration:none">Back to Login</a>
                    """
                : """
                    <h1>Forgot Password</h1>
                    <p class="subtitle">Enter your panel username. A reset link will be sent to your in-game chat.</p>
                    <form method="POST" action="/forgot-password">
                      <div class="form-group">
                        <input name="username" type="text" placeholder="Username" autocomplete="username" autofocus required>
                      </div>
                      <button type="submit" class="btn btn-primary">Send Reset Link</button>
                    </form>
                    <p class="hint"><a href="/login" style="color:var(--purple-muted)">Back to Login</a></p>
                    """;

        return ("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>SwagAPI — Forgot Password</title>
                """ + AUTH_PAGE_STYLE + """
                </head>
                <body>
                <div class="overlay">
                  <div class="login-bg"></div>
                  <div class="login-card">
                    <div class="brand-mark"><span class="brand-dot"></span>SwagAPI</div>
                    {{BODY}}
                    <a class="brand-wordmark" href="https://swag617.github.io/" target="_blank" rel="noopener">swag617</a>
                  </div>
                </div>
                </body>
                </html>
                """).replace("{{BODY}}", body);
    }

    private String buildResetPasswordPage(String token, String username, String error) {
        String tokenValue = escapeHtmlAttr(token == null ? "" : token);
        String errorBlock = error != null ? error : "";

        String body = username != null
                ? ("""
                    <h1>Reset Password</h1>
                    <p class="subtitle">Choose a new password for <strong>{{USERNAME}}</strong></p>
                    <form method="POST" action="/reset-password">
                      <input type="hidden" name="token" value="{{TOKEN}}">
                      <div class="form-group">
                        <input name="password" type="password" placeholder="New password (min 8 characters)" autocomplete="new-password" autofocus required>
                      </div>
                      <div class="form-group">
                        <input name="confirm" type="password" placeholder="Confirm new password" autocomplete="new-password" required>
                      </div>
                      <button type="submit" class="btn btn-primary">Reset Password</button>
                    </form>
                    <p class="error-msg">{{ERROR}}</p>
                    """).replace("{{USERNAME}}", escapeHtmlAttr(username)).replace("{{TOKEN}}", tokenValue).replace("{{ERROR}}", errorBlock)
                : """
                    <h1>Link Invalid</h1>
                    <p class="subtitle">This reset link is invalid or has expired.</p>
                    <p class="hint">Request a new one from the <a href="/forgot-password" style="color:var(--purple-muted)">forgot password</a> page.</p>
                    """;

        return ("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>SwagAPI — Reset Password</title>
                """ + AUTH_PAGE_STYLE + """
                </head>
                <body>
                <div class="overlay">
                  <div class="login-bg"></div>
                  <div class="login-card">
                    <div class="brand-mark"><span class="brand-dot"></span>SwagAPI</div>
                    {{BODY}}
                    <a class="brand-wordmark" href="https://swag617.github.io/" target="_blank" rel="noopener">swag617</a>
                  </div>
                </div>
                </body>
                </html>
                """).replace("{{BODY}}", body);
    }

    /** Shared content-area style for /account and /settings — small pages under the shared topbar. */
    private static final String SUB_PAGE_STYLE = """
            <style>
            :root{
              --bg:#16161c;--bg-card:#1c1c24;--border:#2a2a35;--border-dim:#24242e;
              --blue:#667eea;--purple:#764ba2;--purple-muted:#9b8ec4;
              --grad:linear-gradient(135deg,#667eea 0%,#764ba2 100%);
              --text:#e4e4ea;--text-muted:#8b8b96;--text-dim:#5c5c68;
              --green:#5fe05f;--danger:#ec5f5f;--chip-bg:#24242f;
              --font:'Inter','Segoe UI',system-ui,sans-serif;--mono:'JetBrains Mono',monospace;
            }
            *{box-sizing:border-box;margin:0;padding:0}
            body{background:var(--bg);color:var(--text);font-family:var(--font);min-height:100vh}
            .content{max-width:800px;margin:0 auto;padding:1.6rem}
            .content h2{font-size:1.05rem;font-weight:700;margin-bottom:1.1rem;letter-spacing:-.01em}
            .content h3{font-family:var(--mono);font-size:.68rem;letter-spacing:.1em;text-transform:uppercase;color:var(--text-muted);margin:2rem 0 .9rem;padding-bottom:.5rem;border-bottom:1px solid var(--border-dim)}
            .card{background:var(--bg-card);border:1px solid var(--border-dim);border-radius:10px;padding:1.25rem}
            .banner{padding:.7rem 1rem;border-radius:8px;font-size:.85rem;margin-bottom:1.1rem}
            .banner.error{background:rgba(236,95,95,.1);border:1px solid rgba(236,95,95,.3);color:var(--danger)}
            .banner.success{background:rgba(95,224,95,.1);border:1px solid rgba(95,224,95,.3);color:var(--green)}
            .form-group{margin-bottom:1.1rem}
            .form-group label{display:block;font-family:var(--mono);font-size:.68rem;text-transform:uppercase;letter-spacing:.08em;color:var(--text-muted);margin-bottom:.4rem}
            .form-group input,.form-group select{width:100%;padding:.6rem .8rem;background:var(--bg);border:1px solid var(--border);border-radius:8px;color:var(--text);font-size:.9rem;font-family:var(--font);outline:none}
            .form-group input:focus,.form-group select:focus{border-color:var(--purple)}
            .checkbox-row{display:flex;align-items:center;gap:.5rem;margin-bottom:1.1rem}
            .checkbox-row input{width:16px;height:16px;accent-color:var(--purple)}
            .checkbox-row label{font-size:.85rem;color:var(--text)}
            .btn{display:inline-block;padding:.55rem 1.1rem;border:none;border-radius:8px;cursor:pointer;font-family:var(--font);font-size:.85rem;font-weight:600;transition:transform .15s,box-shadow .15s}
            .btn-primary{background:var(--grad);color:#fff}
            .btn-primary:hover{box-shadow:0 6px 18px rgba(102,126,234,.35);transform:translateY(-1px)}
            .btn-sm{padding:.32rem .7rem;font-size:.76rem}
            .btn-ghost{background:var(--chip-bg);color:var(--text-muted);border:1px solid var(--border-dim)}
            .btn-ghost:hover{color:var(--text)}
            .data-table{width:100%;border-collapse:collapse;border:1px solid var(--border-dim);border-radius:10px;overflow:hidden}
            .data-table th,.data-table td{padding:.6rem .85rem;text-align:left;font-size:.82rem;border-bottom:1px solid var(--border-dim)}
            .data-table th{color:var(--text-muted);font-weight:600;background:var(--bg-card);font-family:var(--mono);font-size:.68rem;text-transform:uppercase;letter-spacing:.05em}
            .data-table tbody tr{background:var(--bg-card)}
            .data-table tr:last-child td{border-bottom:none}
            .data-table td form{margin:0}
            .hint{color:var(--text-dim);font-size:.78rem;margin-top:.4rem}
            </style>
            """;

    private String buildAccountPage(HttpExchange exchange, String error, String success) {
        Session session = lookupSession(exchange, false);
        String currentUsername = session != null ? session.username() : "";

        String banner = error != null ? "<div class=\"banner error\">" + escapeHtmlAttr(error) + "</div>"
                : success != null ? "<div class=\"banner success\">" + escapeHtmlAttr(success) + "</div>"
                : "";

        StringBuilder rows = new StringBuilder();
        for (AccountSummary acc : getAccountSummaries()) {
            String linked = acc.linkedPlayerName() != null ? escapeHtmlAttr(acc.linkedPlayerName()) : "&mdash;";
            String created = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
                    .format(java.time.Instant.ofEpochMilli(acc.created()).atZone(java.time.ZoneId.systemDefault()));
            rows.append("<tr><td>").append(escapeHtmlAttr(acc.username())).append("</td><td>").append(linked)
                    .append("</td><td>").append(created).append("</td><td>")
                    .append("<form method=\"POST\" action=\"/account/reset\"><input type=\"hidden\" name=\"username\" value=\"")
                    .append(escapeHtmlAttr(acc.username())).append("\"><button type=\"submit\" class=\"btn btn-ghost btn-sm\">Send Reset Link</button></form>")
                    .append("</td></tr>");
        }

        return ("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>SwagAPI — Account</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
                <link rel="stylesheet" href="/swagapi/shared/topbar.css">
                <script>window.SWAG_TOPBAR = {plugin: "Account"};</script>
                """ + SUB_PAGE_STYLE + """
                </head>
                <body>
                <div class="content">
                  <h2>Account</h2>
                  {{BANNER}}
                  <div class="card">
                    <p class="hint" style="margin-bottom:1.1rem">Signed in as <strong>{{CURRENT}}</strong></p>
                    <form method="POST" action="/account">
                      <div class="form-group">
                        <label for="current">Current password</label>
                        <input id="current" name="current" type="password" autocomplete="current-password" required>
                      </div>
                      <div class="form-group">
                        <label for="password">New password</label>
                        <input id="password" name="password" type="password" placeholder="Min 8 characters" autocomplete="new-password" required>
                      </div>
                      <div class="form-group">
                        <label for="confirm">Confirm new password</label>
                        <input id="confirm" name="confirm" type="password" autocomplete="new-password" required>
                      </div>
                      <button type="submit" class="btn btn-primary">Change Password</button>
                    </form>
                  </div>

                  <h3>All Accounts</h3>
                  <table class="data-table">
                    <thead><tr><th>Username</th><th>Linked Player</th><th>Created</th><th></th></tr></thead>
                    <tbody>{{ROWS}}</tbody>
                  </table>
                </div>
                <script src="/swagapi/shared/topbar.js"></script>
                </body>
                </html>
                """).replace("{{BANNER}}", banner).replace("{{CURRENT}}", escapeHtmlAttr(currentUsername)).replace("{{ROWS}}", rows.toString());
    }

    private String buildSettingsPage(String error, String success) {
        String banner = error != null ? "<div class=\"banner error\">" + escapeHtmlAttr(error) + "</div>"
                : success != null ? "<div class=\"banner success\">" + escapeHtmlAttr(success) + "</div>"
                : "";

        int sessionDays = plugin.getConfig().getInt("web-server.auth.session-days", 30);
        int setupMinutes = plugin.getConfig().getInt("web-server.auth.setup-token-minutes", 15);
        boolean remindOnJoin = plugin.getConfig().getBoolean("web-server.auth.remind-ops-on-join", true);
        boolean authOn = plugin.getConfig().getBoolean("web-server.auth.enabled", true);
        String dbType = plugin.getConfig().getString("database.type", "sqlite");
        boolean economyOn = plugin.getEconomyService() != null && plugin.getEconomyService().isEnabled();
        UpdateService us = plugin.getUpdateService();

        String infoRows =
                "<tr><td>Database type</td><td>" + escapeHtmlAttr(dbType) + "</td></tr>"
                + "<tr><td>Economy (Vault)</td><td>" + (economyOn ? "Enabled" : "Disabled") + "</td></tr>"
                + "<tr><td>Update system</td><td>" + (us != null && us.isEnabled() ? "Enabled" : "Disabled") + "</td></tr>"
                + "<tr><td>Web server port</td><td>" + port + "</td></tr>"
                + "<tr><td>Web server bind address</td><td>" + escapeHtmlAttr(bindAddress) + "</td></tr>"
                + "<tr><td>Registered web modules</td><td>" + registeredModules.size() + "</td></tr>";

        return ("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>SwagAPI — Settings</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
                <link rel="stylesheet" href="/swagapi/shared/topbar.css">
                <script>window.SWAG_TOPBAR = {plugin: "Settings"};</script>
                """ + SUB_PAGE_STYLE + """
                </head>
                <body>
                <div class="content">
                  <h2>Settings</h2>
                  {{BANNER}}

                  <h3>Web Panel Settings</h3>
                  <div class="card">
                    <form method="POST" action="/settings">
                      <div class="checkbox-row">
                        <input type="checkbox" id="auth-enabled" name="auth-enabled" value="true" {{AUTH_CHECKED}}>
                        <label for="auth-enabled">Require login (disabling exposes the panel to anyone who can reach the port)</label>
                      </div>
                      <div class="checkbox-row">
                        <input type="checkbox" id="remind-ops-on-join" name="remind-ops-on-join" value="true" {{REMIND_CHECKED}}>
                        <label for="remind-ops-on-join">Remind OPs with no account to create one on join</label>
                      </div>
                      <div class="form-group">
                        <label for="session-days">Session length (days) &mdash; slides forward on every visit</label>
                        <input id="session-days" name="session-days" type="number" min="1" value="{{SESSION_DAYS}}" required>
                      </div>
                      <div class="form-group">
                        <label for="setup-token-minutes">Setup/reset link expiry (minutes)</label>
                        <input id="setup-token-minutes" name="setup-token-minutes" type="number" min="1" value="{{SETUP_MINUTES}}" required>
                      </div>
                      <button type="submit" class="btn btn-primary">Save Settings</button>
                    </form>
                  </div>

                  <h3>System Info <span style="text-transform:none;letter-spacing:0;font-family:var(--font)">(read-only)</span></h3>
                  <table class="data-table">
                    <tbody>{{INFO_ROWS}}</tbody>
                  </table>
                </div>
                <script src="/swagapi/shared/topbar.js"></script>
                </body>
                </html>
                """).replace("{{BANNER}}", banner)
                    .replace("{{AUTH_CHECKED}}", authOn ? "checked" : "")
                    .replace("{{REMIND_CHECKED}}", remindOnJoin ? "checked" : "")
                    .replace("{{SESSION_DAYS}}", String.valueOf(sessionDays))
                    .replace("{{SETUP_MINUTES}}", String.valueOf(setupMinutes))
                    .replace("{{INFO_ROWS}}", infoRows);
    }

    private String buildHomePage() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>SwagAPI — Control Panel</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
                <link rel="stylesheet" href="/swagapi/shared/topbar.css">
                <style>
                :root{
                  --bg:#16161c;--bg-card:#1c1c24;--bg-row-alt:#1f1f29;
                  --border:#2a2a35;--border-dim:#24242e;
                  --blue:#667eea;--purple:#764ba2;--purple-muted:#9b8ec4;
                  --grad:linear-gradient(135deg,#667eea 0%,#764ba2 100%);
                  --text:#e4e4ea;--text-muted:#8b8b96;--text-dim:#5c5c68;
                  --green:#5fe05f;--yellow:#f0c060;--danger:#ec5f5f;--chip-bg:#24242f;
                  --font:'Inter','Segoe UI',system-ui,sans-serif;--mono:'JetBrains Mono',monospace;
                }
                *{box-sizing:border-box;margin:0;padding:0}
                body{background:var(--bg);color:var(--text);font-family:var(--font);min-height:100vh;display:flex;flex-direction:column}
                ::-webkit-scrollbar{width:8px;height:8px}
                ::-webkit-scrollbar-thumb{background:var(--purple);border-radius:4px}
                .page-body{display:flex;flex:1;width:100%;align-items:flex-start}
                .sidebar{width:220px;flex-shrink:0;background:var(--bg-card);border-right:1px solid var(--border-dim);padding:1.25rem 1rem;transition:width .15s,padding .15s;overflow:hidden;position:sticky;top:53px;align-self:stretch}
                .sidebar.collapsed{width:0;padding-left:0;padding-right:0;border-right:none}
                .sidebar-toggle{display:flex;align-items:center;justify-content:center;background:var(--chip-bg);border:1px solid var(--border-dim);color:var(--text-muted);border-radius:6px;width:26px;height:26px;cursor:pointer;margin-bottom:1.25rem;font-size:.85rem;transition:color .15s}
                .sidebar-toggle:hover{color:var(--text)}
                .sidebar-section{margin-bottom:1.5rem;white-space:nowrap}
                .sidebar-section h4{font-family:var(--mono);font-size:.62rem;text-transform:uppercase;letter-spacing:.08em;color:var(--text-dim);margin-bottom:.5rem}
                .sidebar-section a{display:flex;align-items:center;gap:.5rem;padding:.45rem .6rem;border-radius:6px;color:var(--text-muted);text-decoration:none;font-size:.83rem;transition:background .15s,color .15s}
                .sidebar-section a:hover{background:var(--chip-bg);color:var(--text)}
                .sidebar-expand{display:none;position:fixed;left:.75rem;top:64px;z-index:900;align-items:center;justify-content:center;background:var(--chip-bg);border:1px solid var(--border-dim);color:var(--text-muted);border-radius:6px;width:26px;height:26px;cursor:pointer;font-size:.85rem}
                .sidebar-expand.show{display:flex}
                .main-area{flex:1;min-width:0;display:flex;flex-direction:column}
                .content{max-width:1200px;margin:0 auto;padding:1.6rem;flex:1;width:100%}
                .sh{font-family:var(--mono);font-size:.68rem;letter-spacing:.1em;text-transform:uppercase;color:var(--text-muted);margin-bottom:.9rem;padding-bottom:.5rem;border-bottom:1px solid var(--border-dim);display:flex;justify-content:space-between;align-items:center}
                .sh:not(:first-child){margin-top:2.25rem}
                .modules-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:1rem}
                .card{background:var(--bg-card);border:1px solid var(--border-dim);border-radius:12px;padding:1.25rem;display:block;text-decoration:none;color:inherit;transition:border-color .15s,transform .15s,box-shadow .15s;cursor:pointer}
                .card:hover{border-color:var(--border-accent,rgba(118,75,162,.4));transform:translateY(-2px);box-shadow:0 8px 22px rgba(0,0,0,.3)}
                .card h2{font-size:1rem;margin:.5rem 0 .3rem;font-weight:700}
                .card p{color:var(--text-muted);font-size:.8rem;margin-bottom:.9rem}
                .open-btn{display:inline-block;background:var(--grad);color:#fff;font-size:.72rem;font-weight:600;padding:.32rem .75rem;border-radius:6px}
                .badge{display:inline-block;font-size:.64rem;font-weight:600;padding:.15rem .5rem;border-radius:5px;letter-spacing:.03em}
                .badge-green{background:rgba(95,224,95,.13);color:var(--green)}
                .badge-red{background:rgba(236,95,95,.13);color:var(--danger)}
                .badge-gray{background:rgba(139,139,150,.13);color:var(--text-muted)}
                .empty{color:var(--text-muted);font-size:.85rem;padding:.5rem 0}
                .info-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(175px,1fr));gap:.75rem}
                .info-card{background:var(--bg-card);border:1px solid var(--border-dim);border-radius:10px;padding:1rem}
                .info-card .label{color:var(--text-muted);font-family:var(--mono);font-size:.62rem;text-transform:uppercase;letter-spacing:.08em;margin-bottom:.4rem}
                .info-card .val{font-size:.9rem;font-weight:600;word-break:break-word;line-height:1.4}
                .data-table{width:100%;border-collapse:collapse;font-size:.85rem;background:var(--bg-card);border:1px solid var(--border-dim);border-radius:10px;overflow:hidden}
                .data-table th{text-align:left;padding:.6rem .9rem;color:var(--text-muted);font-family:var(--mono);font-size:.62rem;text-transform:uppercase;letter-spacing:.08em;border-bottom:1px solid var(--border-dim);font-weight:600}
                .data-table td{padding:.6rem .9rem;border-bottom:1px solid var(--border-dim)}
                .data-table tr:last-child td{border-bottom:none}
                .data-table tr:nth-child(even){background:var(--bg-row-alt)}
                .env-badge{display:inline-block;font-size:.65rem;padding:.15rem .5rem;border-radius:5px;background:var(--chip-bg);border:1px solid var(--border);color:var(--purple-muted)}
                .plugin-list{background:var(--bg-card);border:1px solid var(--border-dim);border-radius:10px;padding:1.1rem 1.25rem;display:flex;flex-wrap:wrap;gap:.5rem .9rem}
                .plugin-ok{color:var(--green);font-size:.82rem;display:inline-flex;align-items:center;gap:.35rem}
                .plugin-off{color:var(--danger);font-size:.82rem;display:inline-flex;align-items:center;gap:.35rem}
                footer{padding:1.1rem 1.6rem;border-top:1px solid var(--border-dim);display:flex;justify-content:space-between;align-items:center;color:var(--text-dim);font-size:.76rem;flex-shrink:0;flex-wrap:wrap;gap:.4rem}
                footer a{color:var(--purple-muted);text-decoration:none}
                footer a:hover{color:var(--blue)}
                .wordmark{background:var(--grad);-webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;font-weight:700;text-decoration:none}
                </style>
                </head>
                <body>
                <script>window.SWAG_TOPBAR = {};</script>
                <button class="sidebar-expand" id="sidebar-expand" title="Expand sidebar">&raquo;</button>
                <div class="page-body">
                  <aside class="sidebar" id="sidebar">
                    <button class="sidebar-toggle" id="sidebar-toggle" title="Collapse sidebar">&laquo;</button>

                    <div class="sidebar-section">
                      <h4>Account</h4>
                      <a href="/account">&#128100; Manage Account</a>
                    </div>

                    <div class="sidebar-section">
                      <h4>Settings</h4>
                      <a href="/settings">&#9881; Web Panel Settings</a>
                    </div>

                    <div class="sidebar-section">
                      <h4>Links</h4>
                      <a href="https://github.com/swag617" target="_blank" rel="noopener">&#128187; GitHub</a>
                      <a href="https://discord.gg/9rKuThh6yU" target="_blank" rel="noopener">&#128172; Discord</a>
                    </div>

                    <div class="sidebar-section">
                      <h4>Wikis</h4>
                      <a href="https://swag617.github.io/" target="_blank" rel="noopener">&#127968; Swag617 Home</a>
                      <a href="https://swag617.github.io/swagfishing-docs/" target="_blank" rel="noopener">&#128220; SwagFishing Docs</a>
                    </div>
                  </aside>

                  <div class="main-area">
                    <div class="content">

                      <div class="sh"><span>Plugin Modules</span><span id="mod-count"></span></div>
                      <div id="modules" class="modules-grid"><p class="empty">Loading&hellip;</p></div>

                      <div class="sh"><span>Server Info</span></div>
                      <div class="info-grid">
                        <div class="info-card"><div class="label">Software</div><div id="version" class="val">—</div></div>
                        <div class="info-card"><div class="label">Uptime</div><div id="uptime2" class="val">—</div></div>
                        <div class="info-card"><div class="label">HTTP Port</div><div id="port" class="val">—</div></div>
                        <div class="info-card"><div class="label">Total Plugins</div><div id="pcount" class="val">—</div></div>
                        <div class="info-card"><div class="label">Web Modules</div><div id="mcount" class="val">—</div></div>
                        <div class="info-card"><div class="label">TPS 5m / 15m</div><div id="tps-long" class="val">—</div></div>
                        <div class="info-card"><div class="label">Active Sessions</div><div id="sessions" class="val">—</div></div>
                        <div class="info-card"><div class="label">MOTD</div><div id="motd" class="val">—</div></div>
                      </div>

                      <div class="sh"><span>Worlds</span></div>
                      <table class="data-table">
                        <thead><tr><th>Name</th><th>Environment</th><th>Loaded Chunks</th></tr></thead>
                        <tbody id="worlds"></tbody>
                      </table>

                      <div class="sh"><span>Loaded Plugins</span><span id="plugin-count-label"></span></div>
                      <div id="plist" class="plugin-list"><span class="empty">Loading&hellip;</span></div>
                    </div>
                    <footer>
                      <span>SwagAPI Control Panel &mdash; <a href="/swagapi/">API JSON</a> &mdash; <a href="/api/status">Status JSON</a></span>
                      <span><span id="ts"></span> &mdash; <a class="wordmark" href="https://swag617.github.io/" target="_blank" rel="noopener">swag617</a></span>
                    </footer>
                  </div>
                </div>
                <script src="/swagapi/shared/topbar.js"></script>
                <script>
                (function(){
                  var sidebar = document.getElementById('sidebar');
                  var collapseBtn = document.getElementById('sidebar-toggle');
                  var expandBtn = document.getElementById('sidebar-expand');
                  var collapsed = localStorage.getItem('swag_sidebar_collapsed') === 'true';
                  function apply(){
                    sidebar.classList.toggle('collapsed', collapsed);
                    expandBtn.classList.toggle('show', collapsed);
                  }
                  apply();
                  collapseBtn.addEventListener('click', function(){ collapsed = true; localStorage.setItem('swag_sidebar_collapsed', 'true'); apply(); });
                  expandBtn.addEventListener('click', function(){ collapsed = false; localStorage.setItem('swag_sidebar_collapsed', 'false'); apply(); });
                })();
                </script>
                <script>
                function fmtUp(ms){var h=Math.floor(ms/3600000),m=Math.floor((ms%3600000)/60000),s=Math.floor((ms%60000)/1000);return h>0?h+'h '+m+'m':m>0?m+'m '+s+'s':s+'s'}
                // The shared topbar (topbar.js) owns the /api/status poll and the Players/TPS/Mem/Uptime
                // chips; this page just listens for its broadcast to fill in everything else.
                window.addEventListener('swag-status', function(e){
                  try{
                    var d=e.detail;
                    var t5=parseFloat(d.tps['5m']),t15=parseFloat(d.tps['15m']);
                    document.getElementById('tps-long').textContent=t5.toFixed(1)+' / '+t15.toFixed(1);
                    var up=fmtUp(d.uptime);
                    document.getElementById('uptime2').textContent=up;
                    document.getElementById('sessions').textContent=d.sessions;
                    if(d.motd)document.getElementById('motd').textContent=d.motd.replace(/§./g,'');
                    document.getElementById('version').textContent=d.version;
                    document.getElementById('port').textContent=d.port;
                    document.getElementById('pcount').textContent=d.plugins.length;
                    document.getElementById('mcount').textContent=d.modules.length;
                    document.getElementById('mod-count').textContent=d.modules.length+' registered';
                    var g=document.getElementById('modules');
                    g.innerHTML=d.modules.length?d.modules.map(function(m){var n=m.charAt(0).toUpperCase()+m.slice(1);
                      return '<a class="card" href="/swagapi/'+m+'/"><span class="badge badge-green">Active</span><h2>'+n+'</h2><p>'+m+' plugin module</p><span class="open-btn">Open &rarr;</span></a>';
                    }).join(''):'<p class="empty">No modules registered yet. Plugin web UIs will appear here as dependent plugins load.</p>';
                    document.getElementById('worlds').innerHTML=d.worlds.map(function(w){
                      return '<tr><td>'+w.name+'</td><td><span class="env-badge">'+w.env.toLowerCase()+'</span></td><td>'+w.chunks+'</td></tr>';
                    }).join('');
                    document.getElementById('plugin-count-label').textContent=d.plugins.length+' loaded';
                    document.getElementById('plist').innerHTML=d.plugins.map(function(p){
                      return '<span class="'+(p.enabled?'plugin-ok':'plugin-off')+'" title="v'+p.version+'">● '+p.name+'</span>';
                    }).join('');
                    document.getElementById('ts').textContent='Updated '+new Date().toLocaleTimeString();
                  }catch(e){document.getElementById('ts').textContent='Connection error';console.error(e);}
                });
                </script>
                </body>
                </html>
                """;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeHtmlAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ─── Config patching ────────────────────────────────────────────────────
    // Targeted text edits to config.yml — never a full YamlConfiguration#save, which would
    // silently strip every comment in the file. Only touches the exact "key: value" line
    // being changed, under the given (possibly nested) section.

    private String readConfigFile() throws IOException {
        return java.nio.file.Files.readString(
                new File(plugin.getDataFolder(), "config.yml").toPath(), StandardCharsets.UTF_8);
    }

    private void writeConfigFile(String text) throws IOException {
        java.nio.file.Files.writeString(
                new File(plugin.getDataFolder(), "config.yml").toPath(), text, StandardCharsets.UTF_8);
    }

    private static String setConfigScalar(String text, String[] sectionPath, String key, String newValue) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int[] range = resolveConfigSection(lines, sectionPath);
        if (range == null) return text;
        int indent = sectionPath.length * 2;

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^(" + " ".repeat(indent) + java.util.regex.Pattern.quote(key) + ":\\s*)([^#]*?)(\\s*#.*)?$");
        for (int i = range[0]; i < range[1]; i++) {
            java.util.regex.Matcher m = p.matcher(lines.get(i));
            if (m.matches()) {
                String comment = m.group(3) != null ? m.group(3) : "";
                lines.set(i, m.group(1) + newValue + comment);
                return String.join("\n", lines);
            }
        }
        return text;
    }

    /** Finds the [start,end) content range of a nested "key:" block, walking one section per path segment. */
    private static int[] resolveConfigSection(List<String> lines, String[] sectionPath) {
        if (sectionPath.length == 0) return new int[]{0, lines.size()};
        int from = 0, to = lines.size(), indent = 0;
        for (String seg : sectionPath) {
            int[] block = findConfigBlock(lines, seg, indent, from, to);
            if (block == null) return null;
            from = block[0] + 1;
            to = block[1];
            indent += 2;
        }
        return new int[]{from, to};
    }

    private static int[] findConfigBlock(List<String> lines, String key, int indent, int from, int to) {
        String headerRegex = "^" + " ".repeat(indent) + java.util.regex.Pattern.quote(key) + ":\\s*(#.*)?$";
        for (int i = from; i < to; i++) {
            if (lines.get(i).matches(headerRegex)) {
                int end = to;
                for (int j = i + 1; j < to; j++) {
                    String l = lines.get(j);
                    if (l.isBlank()) continue;
                    String trimmed = l.stripLeading();
                    if (trimmed.startsWith("#")) continue;
                    int lineIndent = l.length() - trimmed.length();
                    if (lineIndent <= indent) { end = j; break; }
                }
                return new int[]{i, end};
            }
        }
        return null;
    }

    /**
     * The host used in every URL SwagAPI shows to admins (home dashboard, module links, setup
     * and password-reset links). If {@code web-server.public-address} is set, it always wins —
     * that's the whole point of the setting: let an admin override the auto-detected address
     * with the server's actual reachable hostname/IP for non-LAN admins. Otherwise falls back
     * to scanning local network interfaces for a LAN IP, which is only ever correct for admins
     * on the same LAN as the server.
     */
    private String resolveDisplayIp() {
        if (publicAddress != null && !publicAddress.isBlank()) {
            return publicAddress;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    // ─── PrefixStrippingHandler ───────────────────────────────────────────────

    private static final class PrefixStrippingHandler implements HttpHandler {

        private final String prefix;
        private final HttpHandler delegate;

        PrefixStrippingHandler(String prefix, HttpHandler delegate) {
            this.prefix   = prefix;
            this.delegate = delegate;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            delegate.handle(new PrefixStrippedExchange(exchange, prefix));
        }
    }

    // ─── PrefixStrippedExchange ───────────────────────────────────────────────

    private static final class PrefixStrippedExchange extends HttpExchange {

        private final HttpExchange delegate;
        private final String prefix;

        PrefixStrippedExchange(HttpExchange delegate, String prefix) {
            this.delegate = delegate;
            this.prefix   = prefix;
        }

        @Override
        public URI getRequestURI() {
            URI original = delegate.getRequestURI();
            String path  = original.getRawPath();
            if (path.startsWith(prefix)) path = "/" + path.substring(prefix.length());
            String raw = path;
            if (original.getRawQuery()    != null) raw += "?" + original.getRawQuery();
            if (original.getRawFragment() != null) raw += "#" + original.getRawFragment();
            try { return new URI(raw); } catch (URISyntaxException e) { return original; }
        }

        @Override public Headers getRequestHeaders()                                                   { return delegate.getRequestHeaders(); }
        @Override public Headers getResponseHeaders()                                                  { return delegate.getResponseHeaders(); }
        @Override public String getRequestMethod()                                                     { return delegate.getRequestMethod(); }
        @Override public HttpContext getHttpContext()                                                   { return delegate.getHttpContext(); }
        @Override public void close()                                                                  { delegate.close(); }
        @Override public InputStream getRequestBody()                                                  { return delegate.getRequestBody(); }
        @Override public OutputStream getResponseBody()                                                { return delegate.getResponseBody(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) throws IOException   { delegate.sendResponseHeaders(rCode, responseLength); }
        @Override public InetSocketAddress getRemoteAddress()                                          { return delegate.getRemoteAddress(); }
        @Override public int getResponseCode()                                                         { return delegate.getResponseCode(); }
        @Override public InetSocketAddress getLocalAddress()                                           { return delegate.getLocalAddress(); }
        @Override public String getProtocol()                                                          { return delegate.getProtocol(); }
        @Override public Object getAttribute(String name)                                              { return delegate.getAttribute(name); }
        @Override public void setAttribute(String name, Object value)                                  { delegate.setAttribute(name, value); }
        @Override public void setStreams(InputStream i, OutputStream o)                                { delegate.setStreams(i, o); }
        @Override public HttpPrincipal getPrincipal()                                                  { return delegate.getPrincipal(); }
    }
}
