package com.example.wagers;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class WagersPlugin extends JavaPlugin {

    private static WagersPlugin instance;
    private Economy economy;
    private WagerManager wagerManager;
    private MessagesManager messagesManager;
    private PlayerDataManager playerData;
    private EventManager eventManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Vault + an economy plugin (e.g. EssentialsX) is required! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playerData = new PlayerDataManager(this);
        messagesManager = new MessagesManager(this);
        wagerManager = new WagerManager(this);
        eventManager = new EventManager(this);

        getCommand("wager").setExecutor(new WagerCommand(this));
        getCommand("wager").setTabCompleter(new WagerCommand(this));
        getServer().getPluginManager().registerEvents(new FightListener(this), this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new WagersExpansion(this).register();
            getLogger().info("Hooked into PlaceholderAPI - %wagers_...% placeholders registered.");
        }

        getLogger().info("WagersPlugin enabled. Modes: " + WagerMode.list());
    }

    @Override
    public void onDisable() {
        if (eventManager != null) eventManager.shutdown();
        if (wagerManager != null) wagerManager.shutdown();
        if (playerData != null) playerData.save();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public static WagersPlugin get() { return instance; }
    public Economy getEconomy() { return economy; }
    public WagerManager getWagerManager() { return wagerManager; }
    public MessagesManager getMessages() { return messagesManager; }
    public PlayerDataManager getPlayerData() { return playerData; }
    public EventManager getEventManager() { return eventManager; }
}
