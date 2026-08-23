package com.example.wagers;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI placeholders:
 *   %wagers_wins%        - total wins
 *   %wagers_losses%      - total losses
 *   %wagers_money_won%   - total money won
 *   %wagers_money_lost%  - total money lost
 *   %wagers_net%         - net profit
 *   %wagers_messages%    - green ON / red OFF toggle state
 *   %wagers_infight%     - true/false, currently fighting
 *   %wagers_mode%        - current fight mode (or "None")
 *   %wagers_pot%         - current fight pot (or "$0")
 *
 * Event placeholders (rotating events):
 *   %wagers_event_name%      - current/next event name
 *   %wagers_event_time%      - countdown / LIVE timer (auto-updates)
 *   %wagers_event_status%    - Waiting / JOINABLE / LIVE
 *   %wagers_event_players%   - joined (join window) or alive (fight) count
 *   %wagers_event_prize%     - event pot (prize + entry fees)
 *   %wagers_event_mode%      - event fight mode
 *   %wagers_event_animated%  - animated frame that cycles name -> timer -> join hint
 */
public class WagersExpansion extends PlaceholderExpansion {

    private final WagersPlugin plugin;

    public WagersExpansion(WagersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "wagers";
    }

    @Override
    public @NotNull String getAuthor() {
        return "You";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        PlayerDataManager pd = plugin.getPlayerData();
        var id = player.getUniqueId();

        return switch (params.toLowerCase()) {
            case "wins" -> String.valueOf(pd.getWins(id));
            case "losses" -> String.valueOf(pd.getLosses(id));
            case "money_won" -> WagerManager.fmt(pd.getMoneyWon(id));
            case "money_lost" -> WagerManager.fmt(pd.getMoneyLost(id));
            case "net" -> WagerManager.fmt(pd.getMoneyWon(id) - pd.getMoneyLost(id));
            case "messages" -> pd.hasMessagesEnabled(id) ? "§a§lON" : "§c§lOFF";
            case "infight" -> String.valueOf(plugin.getWagerManager().isInWager(id));
            case "mode" -> {
                Wager w = plugin.getWagerManager().getWager(id);
                yield w == null ? "None" : w.getMode().getDisplay();
            }
            case "pot" -> {
                Wager w = plugin.getWagerManager().getWager(id);
                yield w == null ? "$0" : WagerManager.fmt(w.getPot());
            }
            case "event_name" -> plugin.getEventManager().eventName();
            case "event_time" -> plugin.getEventManager().eventTime();
            case "event_status" -> {
                org.bukkit.entity.Player online = player.getPlayer();
                yield plugin.getEventManager().eventStatus(online);
            }
            case "event_players" -> {
                EventManager em = plugin.getEventManager();
                yield String.valueOf(em.getState() == EventManager.State.JOINING
                        ? em.getJoinedCount() : em.getAliveCount());
            }
            case "event_prize" -> WagerManager.fmt(plugin.getEventManager().pot());
            case "event_mode" -> {
                EventManager.EventDef e = plugin.getEventManager().current();
                yield e == null ? "None" : e.mode().getDisplay();
            }
            case "event_animated" -> plugin.getEventManager().animatedFrame();
            default -> null;
        };
    }
}
