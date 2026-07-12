package com.SwagDev.SwagAPI;

import com.SwagDev.SwagAPI.api.*;
import com.SwagDev.SwagAPI.commands.SwagAPICommand;
import com.SwagDev.SwagAPI.listeners.*;
import com.SwagDev.SwagAPI.services.*;
import com.SwagDev.SwagAPI.util.MessageUtil;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class SwagAPI extends JavaPlugin {

    private static SwagAPI instance;
    private DatabaseService databaseService;
    private EconomyService economyService;
    private PlayerDataService playerDataService;
    private MessagingService messagingService;
    private EventBusService eventBusService;
    private MessageUtil messageUtil;
    private UpdateService updateService;
    private WebService webService;

    @Override
    public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();
        saveDefaultConfig();

        databaseService = new DatabaseService(this);
        databaseService.initialize();

        economyService = new EconomyService(this);
        economyService.initialize();

        playerDataService = new PlayerDataService(this, databaseService);
        playerDataService.initialize();

        messagingService = new MessagingService(this);
        messageUtil = new MessageUtil(this);

        eventBusService = new EventBusService(this);

        updateService = new UpdateService(this);
        updateService.initialize();

        webService = new WebService(this);
        webService.initialize();

        ServicesManager sm = getServer().getServicesManager();
        sm.register(IDatabaseService.class,   databaseService,   this, ServicePriority.Normal);
        sm.register(IEconomyService.class,    economyService,    this, ServicePriority.Normal);
        sm.register(IPlayerDataService.class, playerDataService, this, ServicePriority.Normal);
        sm.register(IMessagingService.class,  messagingService,  this, ServicePriority.Normal);
        sm.register(IEventBusService.class,   eventBusService,   this, ServicePriority.Normal);
        sm.register(IUpdateService.class,     updateService,     this, ServicePriority.Normal);
        sm.register(IWebService.class,        webService,        this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(new PlayerSessionListener(this), this);
        getServer().getPluginManager().registerEvents(new AdminJoinUpdateListener(this), this);
        getServer().getPluginManager().registerEvents(new WebAccountReminderListener(this), this);
        SwagAPICommand swagApiCommand = new SwagAPICommand(this);
        getCommand("swagapi").setExecutor(swagApiCommand);
        getCommand("swagapi").setTabCompleter(swagApiCommand);

        getLogger().info("[SwagAPI] Ready in " + (System.currentTimeMillis() - start) + "ms.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[SwagAPI] Shutting down...");
        if (webService        != null) webService.shutdown();
        if (updateService     != null) updateService.shutdown();
        if (playerDataService != null) playerDataService.saveAll();
        if (databaseService   != null) databaseService.shutdown();
        getServer().getServicesManager().unregisterAll(this);
        getLogger().info("[SwagAPI] Disabled.");
    }

    public static SwagAPI getInstance() { return instance; }

    public DatabaseService getDatabaseService()       { return databaseService; }
    public EconomyService getEconomyService()         { return economyService; }
    public PlayerDataService getPlayerDataService()   { return playerDataService; }
    public MessagingService getMessagingService()     { return messagingService; }
    public EventBusService getEventBusService()       { return eventBusService; }
    public MessageUtil getMessageUtil()               { return messageUtil; }
    public UpdateService getUpdateService()           { return updateService; }
    public WebService getWebService()                 { return webService; }
}
