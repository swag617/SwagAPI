package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IUpdateService;
import com.SwagDev.SwagAPI.model.PluginUpdateInfo;
import com.SwagDev.SwagAPI.util.ColorUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class UpdateService implements IUpdateService {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 30_000;

    private final SwagAPI plugin;

    // Config values read during construction
    private boolean enabled;
    private String  manifestUrl;
    private long    checkIntervalHours;
    private boolean notifyOnJoin;
    private boolean autoCheckOnStartup;
    private String  prefix;

    // Runtime state
    private Path stagingDir;

    /** pluginName -> path to the live jar on disk. Written on main thread in initialize(), read-only after. */
    private final Map<String, Path> pluginJarPaths = new HashMap<>();

    /** pluginName -> path to the staged jar waiting to be applied on shutdown. Thread-safe. */
    private final Map<String, Path> stagedJars = new ConcurrentHashMap<>();

    /** Latest known updates from the manifest. Thread-safe for cross-thread read/write. */
    private final List<PluginUpdateInfo> cachedUpdates = new CopyOnWriteArrayList<>();

    private BukkitTask schedulerTask;
    private UpdateShutdownHook shutdownHook;

    // ─────────────────────────────────────────────────────────────────────────

    public UpdateService(SwagAPI plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        enabled            = plugin.getConfig().getBoolean("updates.enabled", true);
        manifestUrl        = plugin.getConfig().getString("updates.manifest-url", "");
        checkIntervalHours = plugin.getConfig().getLong("updates.check-interval-hours", 6);
        notifyOnJoin       = plugin.getConfig().getBoolean("updates.notify-on-join", true);
        autoCheckOnStartup = plugin.getConfig().getBoolean("updates.auto-check-on-startup", true);
        prefix             = plugin.getConfig().getString("updates.prefix", "&8[&bSwagAPI&8] &r");
    }

    public void initialize() {
        if (!enabled) {
            plugin.getLogger().info("[SwagAPI] Update system is disabled.");
            return;
        }

        // 1. Create staging directory
        stagingDir = plugin.getDataFolder().toPath().resolve("staged");
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            plugin.getLogger().warning("[SwagAPI] Could not create staging directory: " + e.getMessage());
        }

        // 2. Cache jar paths for "Swag*" and "StackPlus" plugins (runs on main thread)
        cachePluginJarPaths();

        // 3. Re-register any staged jars left from a previous server run
        scanExistingStagedJars();

        // 4. Register the JVM shutdown hook that applies staged jars
        shutdownHook = new UpdateShutdownHook(stagedJars, pluginJarPaths, plugin.getLogger());
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // 5. Start periodic async update check
        //    Initial delay: 40 ticks (2 s) if auto-check-on-startup is true, else wait one full period
        long periodTicks = checkIntervalHours * 3600L * 20L;
        long initialDelay = autoCheckOnStartup ? 40L : periodTicks;
        schedulerTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::fetchAndCompare, initialDelay, periodTicks);

        plugin.getLogger().info("[SwagAPI] Update system enabled. Manifest: " + manifestUrl);
    }

    public void shutdown() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
        // Do NOT remove shutdownHook — it needs to fire when the JVM exits to apply staged updates.
    }

    // ─── Jar path discovery ───────────────────────────────────────────────────

    /**
     * Populates {@code pluginJarPaths} for all "Swag*" and "StackPlus" plugins.
     * Must be called on the main thread (accesses PluginManager).
     * Tries URLClassLoader first; falls back to JavaPlugin.getFile() via reflection.
     */
    private void cachePluginJarPaths() {
        for (Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
            String name = loaded.getName();
            if (!name.startsWith("Swag") && !name.equals("StackPlus")) continue;

            Path jarPath = resolveJarViaURLClassLoader(loaded);
            if (jarPath == null) {
                jarPath = resolveJarViaReflection(loaded, name);
            }
            if (jarPath != null) {
                pluginJarPaths.put(name, jarPath);
            }
        }
        plugin.getLogger().info("[SwagAPI] Cached jar paths for "
                + pluginJarPaths.size() + " plugin(s).");
    }

    private Path resolveJarViaURLClassLoader(Plugin loaded) {
        try {
            ClassLoader cl = loaded.getClass().getClassLoader();
            if (cl instanceof URLClassLoader) {
                java.net.URL[] urls = ((URLClassLoader) cl).getURLs();
                for (java.net.URL url : urls) {
                    if (url.getFile().endsWith(".jar")) {
                        return Paths.get(url.toURI());
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to reflection approach
        }
        return null;
    }

    private Path resolveJarViaReflection(Plugin loaded, String name) {
        try {
            // JavaPlugin.getFile() is protected — access via reflection
            Method getFile = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredMethod("getFile");
            getFile.setAccessible(true);
            File file = (File) getFile.invoke(loaded);
            return file.toPath();
        } catch (Exception e) {
            plugin.getLogger().warning("[SwagAPI] Could not resolve jar path for "
                    + name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Re-registers staged jars left from a previous server run so the shutdown hook
     * can still apply them if the admin restarts again without re-staging.
     */
    private void scanExistingStagedJars() {
        if (stagingDir == null || !Files.exists(stagingDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDir, "*.staged.jar")) {
            for (Path file : stream) {
                String filename  = file.getFileName().toString();
                String pluginName = filename.replace(".staged.jar", "");
                if (pluginJarPaths.containsKey(pluginName)) {
                    stagedJars.put(pluginName, file);
                    plugin.getLogger().info("[SwagAPI] Re-registered staged update for " + pluginName);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[SwagAPI] Error scanning staged jars: " + e.getMessage());
        }
    }

    // ─── IUpdateService implementation ───────────────────────────────────────

    @Override
    public CompletableFuture<List<PluginUpdateInfo>> checkForUpdates() {
        CompletableFuture<List<PluginUpdateInfo>> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            fetchAndCompare();
            future.complete(new ArrayList<>(cachedUpdates));
        });
        return future;
    }

    @Override
    public List<PluginUpdateInfo> getCachedUpdates() {
        return Collections.unmodifiableList(cachedUpdates);
    }

    @Override
    public Optional<PluginUpdateInfo> getUpdateInfo(String pluginName) {
        return cachedUpdates.stream()
                .filter(info -> info.pluginName().equalsIgnoreCase(pluginName))
                .findFirst();
    }

    @Override
    public CompletableFuture<Boolean> stageUpdate(String pluginName) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                doStageUpdate(pluginName, future);
            } catch (Exception e) {
                plugin.getLogger().warning("[SwagAPI] Unexpected error staging " + pluginName
                        + ": " + e.getMessage());
                future.complete(false);
            }
        });
        return future;
    }

    private void doStageUpdate(String pluginName, CompletableFuture<Boolean> future) {
        Optional<PluginUpdateInfo> infoOpt = getUpdateInfo(pluginName);
        if (infoOpt.isEmpty()) {
            plugin.getLogger().warning("[SwagAPI] No cached update info for: " + pluginName);
            future.complete(false);
            return;
        }
        PluginUpdateInfo info = infoOpt.get();

        if (info.downloadUrl() == null || info.downloadUrl().isEmpty()) {
            plugin.getLogger().warning("[SwagAPI] No download URL for: " + pluginName);
            future.complete(false);
            return;
        }

        Path tmpFile    = stagingDir.resolve(".tmp_" + pluginName + ".jar");
        Path stagedFile = stagingDir.resolve(pluginName + ".staged.jar");

        // Download
        try {
            downloadFile(info.downloadUrl(), tmpFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[SwagAPI] Download failed for " + pluginName
                    + ": " + e.getMessage());
            tryDelete(tmpFile);
            future.complete(false);
            return;
        }

        // Checksum validation
        if (info.expectedChecksum() != null && !info.expectedChecksum().isEmpty()) {
            String actual;
            try {
                actual = sha256(tmpFile);
            } catch (Exception e) {
                plugin.getLogger().warning("[SwagAPI] Checksum error for " + pluginName
                        + ": " + e.getMessage());
                tryDelete(tmpFile);
                future.complete(false);
                return;
            }
            if (!actual.equalsIgnoreCase(info.expectedChecksum())) {
                plugin.getLogger().warning("[SwagAPI] Checksum MISMATCH for " + pluginName
                        + ". Expected: " + info.expectedChecksum() + ", Got: " + actual);
                tryDelete(tmpFile);
                future.complete(false);
                return;
            }
        }

        // Move temp file into final staged position
        try {
            Files.move(tmpFile, stagedFile, StandardCopyOption.REPLACE_EXISTING);
            stagedJars.put(pluginName, stagedFile);
            plugin.getLogger().info("[SwagAPI] Staged update for " + pluginName
                    + " (" + info.currentVersion() + " -> " + info.latestVersion() + ")");
            future.complete(true);
        } catch (IOException e) {
            plugin.getLogger().warning("[SwagAPI] Could not move staged jar for " + pluginName
                    + ": " + e.getMessage());
            tryDelete(tmpFile);
            future.complete(false);
        }
    }

    @Override
    public boolean isStaged(String pluginName) {
        return stagedJars.containsKey(pluginName);
    }

    @Override
    public boolean cancelStagedUpdate(String pluginName) {
        Path staged = stagedJars.remove(pluginName);
        if (staged == null) return false;
        tryDelete(staged);
        plugin.getLogger().info("[SwagAPI] Cancelled staged update for " + pluginName);
        return true;
    }

    @Override
    public List<String> getStagedPlugins() {
        return new ArrayList<>(stagedJars.keySet());
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ─── Manifest fetch & comparison ─────────────────────────────────────────

    /**
     * Fetches the remote manifest and compares each entry against loaded plugins.
     * Called from an async BukkitScheduler thread.
     * Note: Bukkit.getPluginManager().getPlugin() is a read-only operation safe to call
     * from async threads in Paper's ConcurrentHashMap-backed implementation.
     */
    private void fetchAndCompare() {
        if (manifestUrl == null || manifestUrl.isEmpty()) {
            plugin.getLogger().warning("[SwagAPI] No manifest URL configured — skipping update check.");
            return;
        }

        String json;
        try {
            json = fetchUrl(manifestUrl);
        } catch (IOException e) {
            plugin.getLogger().warning("[SwagAPI] Could not fetch update manifest: " + e.getMessage());
            return;
        }

        List<PluginUpdateInfo> updates = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String pluginName = entry.getKey();
                JsonObject obj    = entry.getValue().getAsJsonObject();

                String latestVersion = obj.has("version") ? obj.get("version").getAsString() : "";
                String downloadUrl   = obj.has("url")      ? obj.get("url").getAsString()     : "";
                String checksum      = obj.has("checksum") ? obj.get("checksum").getAsString() : "";

                Plugin loaded = Bukkit.getPluginManager().getPlugin(pluginName);
                if (loaded == null) continue;
                String currentVersion = loaded.getPluginMeta().getVersion();

                if (!currentVersion.equalsIgnoreCase(latestVersion)) {
                    updates.add(new PluginUpdateInfo(
                            pluginName, currentVersion, latestVersion, downloadUrl, checksum));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[SwagAPI] Failed to parse update manifest: " + e.getMessage());
            return;
        }

        cachedUpdates.clear();
        cachedUpdates.addAll(updates);

        if (!updates.isEmpty()) {
            // Switch to main thread to safely interact with online players
            plugin.getServer().getScheduler().runTask(plugin, () -> notifyAdmins(updates));
        }
    }

    // ─── Admin notifications ──────────────────────────────────────────────────

    private void notifyAdmins(List<PluginUpdateInfo> updates) {
        String header = ColorUtil.colorize(prefix + "&e" + updates.size() + " plugin update(s) available!");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("swagapi.admin")) continue;
            player.sendMessage(header);
            for (PluginUpdateInfo info : updates) {
                if (!isStaged(info.pluginName())) {
                    sendUpdateNotification(player, info);
                }
            }
        }
    }

    /**
     * Sends a single clickable update notification to the given player.
     * Public so AdminJoinUpdateListener can call it.
     */
    public void sendUpdateNotification(Player player, PluginUpdateInfo info) {
        Component updateButton = Component.text("[Update]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(
                        "/swagapi update " + info.pluginName() + " --confirm"))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Click to stage update for " + info.pluginName(),
                                NamedTextColor.GRAY)));

        Component msg = ColorUtil.toComponent(prefix
                + "&b" + info.pluginName()
                + " &7" + info.currentVersion()
                + " &8-> &a" + info.latestVersion()
                + " ")
                .append(updateButton);

        player.sendMessage(msg);
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private String fetchUrl(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "SwagAPI-UpdateChecker/1.0");
        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + status + " fetching: " + urlStr);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private void downloadFile(String urlStr, Path dest) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "SwagAPI-UpdateChecker/1.0");
        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + status + " downloading: " + urlStr);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    private String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
