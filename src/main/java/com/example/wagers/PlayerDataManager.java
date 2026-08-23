package com.example.wagers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class PlayerDataManager {

    private final WagersPlugin plugin;
    private File file;
    private FileConfiguration data;

    public PlayerDataManager(WagersPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        file = new File(plugin.getDataFolder(), "data.yml");
        if (!file.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create data.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    /* ---------------- Message toggle (default ON) ---------------- */

    public boolean hasMessagesEnabled(UUID id) {
        return data.getBoolean("players." + id + ".messages", true);
    }

    /** Flip the toggle; returns the new state. */
    public boolean toggleMessages(UUID id) {
        boolean newState = !hasMessagesEnabled(id);
        data.set("players." + id + ".messages", newState);
        save();
        return newState;
    }

    /* ---------------- Stats ---------------- */

    public int getWins(UUID id) {
        return data.getInt("players." + id + ".wins", 0);
    }

    public int getLosses(UUID id) {
        return data.getInt("players." + id + ".losses", 0);
    }

    public double getMoneyWon(UUID id) {
        return data.getDouble("players." + id + ".money-won", 0);
    }

    public double getMoneyLost(UUID id) {
        return data.getDouble("players." + id + ".money-lost", 0);
    }

    public void recordWin(UUID id, double amountWon) {
        data.set("players." + id + ".wins", getWins(id) + 1);
        data.set("players." + id + ".money-won", getMoneyWon(id) + amountWon);
        save();
    }

    public void recordLoss(UUID id, double amountLost) {
        data.set("players." + id + ".losses", getLosses(id) + 1);
        data.set("players." + id + ".money-lost", getMoneyLost(id) + amountLost);
        save();
    }
}
