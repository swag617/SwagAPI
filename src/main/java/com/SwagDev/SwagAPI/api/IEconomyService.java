package com.SwagDev.SwagAPI.api;

import org.bukkit.OfflinePlayer;

public interface IEconomyService {

    boolean isEnabled();

    double getBalance(OfflinePlayer player);

    boolean has(OfflinePlayer player, double amount);

    boolean withdraw(OfflinePlayer player, double amount);

    boolean deposit(OfflinePlayer player, double amount);

    String format(double amount);

    String getCurrencyName();
}
