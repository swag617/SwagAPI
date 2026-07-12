package com.SwagDev.SwagAPI.api;

import com.SwagDev.SwagAPI.model.PlayerDataModule;
import com.SwagDev.SwagAPI.model.SwagPlayerProfile;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface IPlayerDataService {

    SwagPlayerProfile getProfile(UUID uuid);

    SwagPlayerProfile getProfile(Player player);

    boolean isLoaded(UUID uuid);

    CompletableFuture<SwagPlayerProfile> loadProfile(UUID uuid);

    CompletableFuture<Void> saveProfile(UUID uuid);

    void saveAll();

    void registerModule(String pluginKey, PlayerDataModule module);

    <T> T getModuleData(UUID uuid, String pluginKey, Class<T> type);

    void setModuleData(UUID uuid, String pluginKey, Object data);
}
