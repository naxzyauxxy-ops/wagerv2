package com.example.wagers;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Wager {

    public enum State { COUNTDOWN, FIGHTING, ENDED }

    private final UUID player1;
    private final UUID player2;
    private final double amount;      // per-player stake; pot = amount * 2
    private final WagerMode mode;

    private State state = State.COUNTDOWN;

    private final Map<UUID, ItemStack[]> savedInventories = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new HashMap<>();
    private final Map<UUID, Location> savedLocations = new HashMap<>();

    public Wager(UUID player1, UUID player2, double amount, WagerMode mode) {
        this.player1 = player1;
        this.player2 = player2;
        this.amount = amount;
        this.mode = mode;
    }

    public UUID getPlayer1() { return player1; }
    public UUID getPlayer2() { return player2; }
    public double getAmount() { return amount; }
    public double getPot() { return amount * 2; }
    public WagerMode getMode() { return mode; }

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public boolean involves(UUID id) {
        return player1.equals(id) || player2.equals(id);
    }

    public UUID getOpponent(UUID id) {
        return player1.equals(id) ? player2 : player1;
    }

    public void saveSnapshot(UUID id, ItemStack[] contents, ItemStack[] armor, Location loc) {
        savedInventories.put(id, contents);
        savedArmor.put(id, armor);
        savedLocations.put(id, loc);
    }

    public ItemStack[] getSavedInventory(UUID id) { return savedInventories.get(id); }
    public ItemStack[] getSavedArmor(UUID id) { return savedArmor.get(id); }
    public Location getSavedLocation(UUID id) { return savedLocations.get(id); }
}
