package com.SwagDev.SwagAPI.model;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SwagPlayerProfile {

    private final UUID uuid;
    private String username;
    private long firstJoin;
    private long lastSeen;
    private final Map<String, Object> moduleData = new ConcurrentHashMap<>();

    public SwagPlayerProfile(UUID uuid, String username, long firstJoin, long lastSeen) {
        this.uuid = uuid;
        this.username = username;
        this.firstJoin = firstJoin;
        this.lastSeen = lastSeen;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public long getFirstJoin() {
        return firstJoin;
    }

    public void setFirstJoin(long firstJoin) {
        this.firstJoin = firstJoin;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public Object getModuleData(String key) {
        return moduleData.get(key);
    }

    public void setModuleData(String key, Object data) {
        moduleData.put(key, data);
    }

    public Map<String, Object> getAllModuleData() {
        return moduleData;
    }
}
