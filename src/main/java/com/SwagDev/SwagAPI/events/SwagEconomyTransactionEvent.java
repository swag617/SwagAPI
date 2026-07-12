package com.SwagDev.SwagAPI.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class SwagEconomyTransactionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final double amount;
    private final String transactionType;
    private final boolean success;

    public SwagEconomyTransactionEvent(UUID playerUuid, double amount, String transactionType, boolean success) {
        this.playerUuid = playerUuid;
        this.amount = amount;
        this.transactionType = transactionType;
        this.success = success;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public double getAmount() {
        return amount;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public boolean isSuccess() {
        return success;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
