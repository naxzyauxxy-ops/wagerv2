package com.example.wagers;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;

public class MessagesManager {

    private final WagersPlugin plugin;
    private FileConfiguration messages;
    private boolean papi;

    public MessagesManager(WagersPlugin plugin) {
        this.plugin = plugin;
        reload();
        papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
    }

    /**
     * Get a formatted message. Pass placeholder pairs: get("request-sent", null, "%player%", "Steve", "%amount%", "$50")
     * The player (may be null) is used for PlaceholderAPI placeholders.
     */
    public String get(String key, Player context, String... replacements) {
        String raw = messages.getString(key, "&cMissing message: " + key);
        raw = raw.replace("%prefix%", messages.getString("prefix", "&8[&6Wagers&8] &r"));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        if (papi && context != null) {
            raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(context, raw);
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** Raw un-colored message string (no prefix substitution). */
    public String getRaw(String key) {
        return messages.getString(key, "");
    }

    /** The colored prefix on its own. */
    public String getPrefix() {
        return ChatColor.translateAlternateColorCodes('&', messages.getString("prefix", "&8[&6Wagers&8] &r"));
    }

    /** Send a message that always goes through (errors, direct feedback). */
    public void send(Player p, String key, String... replacements) {
        p.sendMessage(get(key, p, replacements));
    }

    /** Send a message only if the player has wager messages toggled ON. Fighters always receive it. */
    public void sendToggleable(Player p, boolean isParticipant, String key, String... replacements) {
        if (!isParticipant && !plugin.getPlayerData().hasMessagesEnabled(p.getUniqueId())) return;
        p.sendMessage(get(key, p, replacements));
    }
}
