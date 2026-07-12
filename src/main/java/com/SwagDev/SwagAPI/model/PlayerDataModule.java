package com.SwagDev.SwagAPI.model;

import com.SwagDev.SwagAPI.api.IDatabaseService;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerDataModule {

    CompletableFuture<Object> load(UUID uuid, IDatabaseService db);

    CompletableFuture<Void> save(UUID uuid, Object data, IDatabaseService db);
}
