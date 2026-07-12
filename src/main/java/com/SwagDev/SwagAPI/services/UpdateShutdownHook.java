package com.SwagDev.SwagAPI.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Logger;

/**
 * JVM shutdown hook that copies all staged plugin jars over their current jar files.
 * This runs AFTER the server process has stopped writing to the jars, making the
 * replacement safe. Staged jars are deleted after a successful copy.
 */
public class UpdateShutdownHook extends Thread {

    private final Map<String, Path> stagedJars;       // pluginName -> staged jar path
    private final Map<String, Path> destinationPaths; // pluginName -> current (live) jar path
    private final Logger logger;

    public UpdateShutdownHook(
            Map<String, Path> stagedJars,
            Map<String, Path> destinationPaths,
            Logger logger) {
        super("SwagAPI-UpdateShutdownHook");
        this.stagedJars = stagedJars;
        this.destinationPaths = destinationPaths;
        this.logger = logger;
    }

    @Override
    public void run() {
        if (stagedJars.isEmpty()) return;

        logger.info("[SwagAPI] Applying " + stagedJars.size() + " staged plugin update(s)...");

        for (Map.Entry<String, Path> entry : stagedJars.entrySet()) {
            String pluginName = entry.getKey();
            Path staged = entry.getValue();
            Path dest = destinationPaths.get(pluginName);

            if (dest == null) {
                logger.warning("[SwagAPI] No destination path for staged plugin: "
                        + pluginName + " — skipping.");
                continue;
            }

            if (!Files.exists(staged)) {
                logger.warning("[SwagAPI] Staged file missing for " + pluginName
                        + ": " + staged + " — skipping.");
                continue;
            }

            try {
                Files.copy(staged, dest, StandardCopyOption.REPLACE_EXISTING);
                logger.info("[SwagAPI] Updated " + pluginName + " -> " + dest.getFileName());
                Files.deleteIfExists(staged);
            } catch (IOException e) {
                logger.warning("[SwagAPI] Failed to apply update for " + pluginName
                        + ": " + e.getMessage());
            }
        }
    }
}
