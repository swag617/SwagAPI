package com.SwagDev.SwagAPI.events;

import com.SwagDev.SwagAPI.model.SwagPlayerProfile;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class SwagPlayerDataSaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID uuid;
    private final SwagPlayerProfile profile;

    public SwagPlayerDataSaveEvent(UUID uuid, SwagPlayerProfile profile) {
        this.uuid = uuid;
        this.profile = profile;
    }

    public UUID getUuid() {
        return uuid;
    }

    public SwagPlayerProfile getProfile() {
        return profile;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
