package com.SwagDev.SwagAPI.api;

import com.SwagDev.SwagAPI.model.PluginUpdateInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface IUpdateService {
    CompletableFuture<List<PluginUpdateInfo>> checkForUpdates();
    List<PluginUpdateInfo> getCachedUpdates();
    Optional<PluginUpdateInfo> getUpdateInfo(String pluginName);
    CompletableFuture<Boolean> stageUpdate(String pluginName);
    boolean isStaged(String pluginName);
    boolean cancelStagedUpdate(String pluginName);
    List<String> getStagedPlugins();
    boolean isEnabled();
}
