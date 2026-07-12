package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.SwagAPI;
import com.SwagDev.SwagAPI.api.IEconomyService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

public class EconomyService implements IEconomyService {

    private final SwagAPI plugin;
    private Economy economy;
    private boolean configEnabled;

    public EconomyService(SwagAPI plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        configEnabled = plugin.getConfig().getBoolean("economy.enabled", true);
        if (!configEnabled) {
            plugin.getLogger().info("Economy integration disabled by config.");
            return;
        }
        // Best-effort at startup — a Vault economy provider (e.g. SwagCore itself)
        // registering later, after SwagAPI has already enabled, is resolved lazily
        // by resolveEconomy() on first real use instead of being missed forever.
        if (resolveEconomy()) {
            plugin.getLogger().info("Economy hooked via Vault: " + economy.getName());
        } else if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault not found yet. Will keep checking on demand.");
        } else {
            plugin.getLogger().warning("No Vault Economy provider found yet. Will keep checking on demand.");
        }
    }

    /** Re-resolves the Vault Economy provider. Cheap to call repeatedly once resolved. */
    private boolean resolveEconomy() {
        if (economy != null) return true;
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return true;
    }

    @Override
    public boolean isEnabled() {
        return configEnabled && resolveEconomy();
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (!isEnabled()) return 0.0;
        return economy.getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (!isEnabled()) return true;
        return economy.has(player, amount);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!isEnabled()) return true;
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (!isEnabled()) return true;
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    @Override
    public String format(double amount) {
        if (!isEnabled()) return String.valueOf(amount);
        return economy.format(amount);
    }

    @Override
    public String getCurrencyName() {
        if (!isEnabled()) return "";
        return economy.currencyNamePlural();
    }
}
