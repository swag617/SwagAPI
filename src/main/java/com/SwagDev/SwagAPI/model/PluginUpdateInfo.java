package com.SwagDev.SwagAPI.model;

public record PluginUpdateInfo(
        String pluginName,
        String currentVersion,
        String latestVersion,
        String downloadUrl,
        String expectedChecksum
) {
    public boolean isUpdateAvailable() {
        return !currentVersion.equalsIgnoreCase(latestVersion);
    }
}
