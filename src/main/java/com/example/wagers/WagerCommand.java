package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class WagerCommand implements CommandExecutor, TabCompleter {

    private final WagersPlugin plugin;

    public WagerCommand(WagersPlugin plugin) {
        this.plugin = plugin;
    }

    private MessagesManager msgs() { return plugin.getMessages(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().get("players-only", null));
            return true;
        }
        WagerManager wm = plugin.getWagerManager();

        if (args.length == 0) {
            help(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "accept" -> wm.acceptRequest(player);
            case "deny", "decline" -> wm.denyRequest(player);
            case "modes" -> {
                msgs().send(player, "modes-header");
                for (WagerMode mode : WagerMode.values()) {
                    msgs().send(player, "modes-entry",
                            "%mode%", mode.getDisplay(), "%description%", mode.getDescription());
                }
            }
            case "messages", "toggle", "msg" -> {
                boolean nowOn = plugin.getPlayerData().toggleMessages(player.getUniqueId());
                msgs().send(player, nowOn ? "messages-enabled" : "messages-disabled");
            }
            case "stats" -> {
                OfflinePlayer target = player;
                if (args.length >= 2) {
                    OfflinePlayer looked = Bukkit.getOfflinePlayer(args[1]);
                    if (looked.getName() != null) target = looked;
                }
                UUID id = target.getUniqueId();
                PlayerDataManager pd = plugin.getPlayerData();
                double net = pd.getMoneyWon(id) - pd.getMoneyLost(id);
                msgs().send(player, "stats-header", "%player%", String.valueOf(target.getName()));
                msgs().send(player, "stats-wins", "%wins%", String.valueOf(pd.getWins(id)));
                msgs().send(player, "stats-losses", "%losses%", String.valueOf(pd.getLosses(id)));
                msgs().send(player, "stats-money-won", "%money_won%", WagerManager.fmt(pd.getMoneyWon(id)));
                msgs().send(player, "stats-money-lost", "%money_lost%", WagerManager.fmt(pd.getMoneyLost(id)));
                msgs().send(player, "stats-net", "%net%", WagerManager.fmt(net));
            }
            case "reload" -> {
                if (!player.hasPermission("wagers.admin")) {
                    help(player);
                    return true;
                }
                plugin.reloadConfig();
                plugin.getMessages().reload();
                player.sendMessage(plugin.getMessages().getPrefix() + "§aConfig and messages reloaded.");
            }
            case "spectate", "spec", "watch" -> {
                if (args.length < 2) {
                    plugin.getSpectatorManager().stop(player, true);
                    return true;
                }
                Player watch = Bukkit.getPlayerExact(args[1]);
                if (watch == null) {
                    msgs().send(player, "player-not-found");
                    return true;
                }
                plugin.getSpectatorManager().spectate(player, watch);
            }
            case "unspectate", "unspec" -> plugin.getSpectatorManager().stop(player, true);
            case "queue", "q" -> {
                if (args.length == 1) {
                    plugin.getQueueManager().status(player);
                    return true;
                }
                if (args[1].equalsIgnoreCase("leave") || args[1].equalsIgnoreCase("exit")) {
                    plugin.getQueueManager().leave(player);
                    return true;
                }
                if (args.length < 3) {
                    msgs().send(player, "queue-usage");
                    return true;
                }
                WagerMode qMode = WagerMode.match(args[1]);
                if (qMode == null) {
                    msgs().send(player, "unknown-mode", "%modes%", WagerMode.list());
                    return true;
                }
                double qAmount = AmountUtil.parse(args[2]);
                if (qAmount <= 0) {
                    msgs().send(player, "invalid-amount", "%input%", args[2]);
                    return true;
                }
                plugin.getQueueManager().join(player, qMode, qAmount);
            }
            case "bet" -> {
                if (args.length < 3) {
                    msgs().send(player, "bet-usage");
                    return true;
                }
                Player fighter = Bukkit.getPlayerExact(args[1]);
                if (fighter == null) {
                    msgs().send(player, "player-not-found");
                    return true;
                }
                double betAmount = AmountUtil.parse(args[2]);
                if (betAmount <= 0) {
                    msgs().send(player, "invalid-amount", "%input%", args[2]);
                    return true;
                }
                plugin.getBettingManager().placeBet(player, fighter, betAmount);
            }
            case "join" -> plugin.getEventManager().join(player);
            case "leave" -> plugin.getEventManager().leave(player);
            case "event" -> {
                EventManager em = plugin.getEventManager();
                EventManager.EventDef e = em.current();
                if (e == null) {
                    msgs().send(player, "event-not-joinable");
                    return true;
                }
                msgs().send(player, "event-info-header");
                msgs().send(player, "event-info-name", "%event%", e.name());
                msgs().send(player, "event-info-mode", "%mode%", e.mode().getDisplay());
                msgs().send(player, "event-info-prize", "%prize%", WagerManager.fmt(em.pot()));
                msgs().send(player, "event-info-status", "%status%", em.eventStatus(player));
                msgs().send(player, "event-info-time", "%time%", em.eventTime());
            }
            case "forfeit" -> {
                Wager w = wm.getWager(player.getUniqueId());
                if (w == null) {
                    msgs().send(player, "not-in-fight");
                    return true;
                }
                wm.endFight(w, w.getOpponent(player.getUniqueId()), player.getUniqueId(), "forfeit");
            }
            default -> {
                if (args.length < 2) {
                    help(player);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || !target.isOnline()) {
                    msgs().send(player, "player-not-found");
                    return true;
                }
                if (target.equals(player)) {
                    msgs().send(player, "cant-wager-self");
                    return true;
                }
                double amount = AmountUtil.parse(args[1]);
                if (amount <= 0) {
                    msgs().send(player, "invalid-amount", "%input%", args[1]);
                    return true;
                }
                WagerMode mode = WagerMode.CLASSIC;
                if (args.length >= 3) {
                    mode = WagerMode.match(args[2]);
                    if (mode == null) {
                        msgs().send(player, "unknown-mode", "%modes%", WagerMode.list());
                        return true;
                    }
                }
                wm.sendRequest(player, target, amount, mode);
            }
        }
        return true;
    }

    private void help(Player p) {
        msgs().send(p, "help-header");
        msgs().send(p, "help-challenge");
        msgs().send(p, "help-accept");
        msgs().send(p, "help-deny");
        msgs().send(p, "help-modes");
        msgs().send(p, "help-forfeit");
        msgs().send(p, "help-toggle");
        msgs().send(p, "help-stats");
        msgs().send(p, "help-join");
        msgs().send(p, "help-event");
        msgs().send(p, "help-queue");
        msgs().send(p, "help-spectate");
        msgs().send(p, "help-bet");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>(Arrays.asList("accept", "deny", "modes", "forfeit", "messages", "stats", "join", "leave", "event", "reload",
                    "queue", "spectate", "bet", "unspectate"));
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            return filter(out, args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("queue") || args[0].equalsIgnoreCase("q")) {
                List<String> opts = new ArrayList<>();
                for (WagerMode m : WagerMode.values()) opts.add(m.getDisplay());
                opts.add("leave");
                return filter(opts, args[1]);
            }
            if (args[0].equalsIgnoreCase("spectate") || args[0].equalsIgnoreCase("spec")
                    || args[0].equalsIgnoreCase("bet")) {
                List<String> names = new ArrayList<>();
                Bukkit.getOnlinePlayers().forEach(pl -> names.add(pl.getName()));
                return filter(names, args[1]);
            }
            if (args[0].equalsIgnoreCase("stats")) {
                List<String> names = new ArrayList<>();
                Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
                return filter(names, args[1]);
            }
            return filter(Arrays.asList("1k", "10k", "100k", "1m"), args[1]);
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("queue") || args[0].equalsIgnoreCase("q")
                    || args[0].equalsIgnoreCase("bet")) {
                return filter(Arrays.asList("1k", "10k", "100k", "1m"), args[2]);
            }
            List<String> modes = new ArrayList<>();
            for (WagerMode m : WagerMode.values()) modes.add(m.getDisplay());
            return filter(modes, args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String input) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(input.toLowerCase())) out.add(o);
        }
        return out;
    }
}
