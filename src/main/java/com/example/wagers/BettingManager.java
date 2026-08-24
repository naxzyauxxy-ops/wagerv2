package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Side betting: anyone not fighting can back a fighter while the pre-fight
 * countdown is running. Payouts are parimutuel - backers of the winner split
 * the losing side's pool in proportion to their own stake, and get their own
 * stake back. If nobody backed the winner, every bet is refunded.
 */
public class BettingManager {

    /** bettor -> amount, per fighter, per wager. */
    private final Map<Wager, Map<UUID, Map<UUID, Double>>> books = new HashMap<>();

    private final WagersPlugin plugin;

    public BettingManager(WagersPlugin plugin) {
        this.plugin = plugin;
    }

    private MessagesManager msgs() {
        return plugin.getMessages();
    }

    public void placeBet(Player bettor, Player fighter, double amount) {
        if (!plugin.getConfig().getBoolean("betting.enabled", true)) {
            msgs().send(bettor, "bet-disabled");
            return;
        }
        Wager wager = plugin.getWagerManager().getWager(fighter.getUniqueId());
        if (wager == null) {
            msgs().send(bettor, "bet-not-fighting", "%player%", fighter.getName());
            return;
        }
        if (wager.involves(bettor.getUniqueId())) {
            msgs().send(bettor, "bet-own-fight");
            return;
        }
        if (wager.getState() != Wager.State.COUNTDOWN) {
            msgs().send(bettor, "bet-closed");
            return;
        }
        double min = plugin.getConfig().getDouble("betting.min-bet", 1.0);
        double max = plugin.getConfig().getDouble("betting.max-bet", 1000000.0);
        if (amount < min || amount > max) {
            msgs().send(bettor, "bet-range",
                    "%min%", WagerManager.fmt(min), "%max%", WagerManager.fmt(max));
            return;
        }
        if (!plugin.getEconomy().has(bettor, amount)) {
            msgs().send(bettor, "not-enough-money", "%amount%", WagerManager.fmt(amount));
            return;
        }

        Map<UUID, Map<UUID, Double>> book = books.computeIfAbsent(wager, w -> new HashMap<>());
        // One side only: you can't back both fighters in the same match
        UUID other = wager.getOpponent(fighter.getUniqueId());
        Map<UUID, Double> otherSide = book.get(other);
        if (otherSide != null && otherSide.containsKey(bettor.getUniqueId())) {
            msgs().send(bettor, "bet-other-side");
            return;
        }

        plugin.getEconomy().withdrawPlayer(bettor, amount);
        Map<UUID, Double> side = book.computeIfAbsent(fighter.getUniqueId(), f -> new HashMap<>());
        double total = side.merge(bettor.getUniqueId(), amount, Double::sum);

        msgs().send(bettor, "bet-placed",
                "%amount%", WagerManager.fmt(amount),
                "%player%", fighter.getName(),
                "%total%", WagerManager.fmt(total));

        // Tell the fighters someone is backing them
        Player backed = Bukkit.getPlayer(fighter.getUniqueId());
        if (backed != null) {
            msgs().send(backed, "bet-backed",
                    "%player%", bettor.getName(), "%amount%", WagerManager.fmt(amount));
        }
    }

    /** Total currently staked on one fighter. */
    public double poolFor(Wager wager, UUID fighter) {
        Map<UUID, Map<UUID, Double>> book = books.get(wager);
        if (book == null) return 0;
        Map<UUID, Double> side = book.get(fighter);
        if (side == null) return 0;
        return side.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** Settle every bet on this wager and pay out the winners. */
    public void settle(Wager wager, UUID winnerId, UUID loserId) {
        Map<UUID, Map<UUID, Double>> book = books.remove(wager);
        if (book == null || book.isEmpty()) return;

        Map<UUID, Double> winningSide = book.getOrDefault(winnerId, Map.of());
        Map<UUID, Double> losingSide = book.getOrDefault(loserId, Map.of());

        double winnerPool = winningSide.values().stream().mapToDouble(Double::doubleValue).sum();
        double loserPool = losingSide.values().stream().mapToDouble(Double::doubleValue).sum();

        // Nobody backed the winner - refund everyone rather than pocket the money
        if (winnerPool <= 0) {
            refundAll(book);
            return;
        }

        double houseCut = Math.max(0, Math.min(0.5, plugin.getConfig().getDouble("betting.house-cut", 0)));
        double distributable = loserPool * (1 - houseCut);

        for (Map.Entry<UUID, Double> entry : winningSide.entrySet()) {
            double stake = entry.getValue();
            double profit = distributable * (stake / winnerPool);
            double payout = stake + profit;

            plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(entry.getKey()), payout);
            Player bettor = Bukkit.getPlayer(entry.getKey());
            if (bettor != null) {
                msgs().send(bettor, "bet-won",
                        "%payout%", WagerManager.fmt(payout),
                        "%profit%", WagerManager.fmt(profit));
            }
        }
        for (UUID loserBettor : losingSide.keySet()) {
            Player bettor = Bukkit.getPlayer(loserBettor);
            if (bettor != null) {
                msgs().send(bettor, "bet-lost",
                        "%amount%", WagerManager.fmt(losingSide.get(loserBettor)));
            }
        }
    }

    /** Give every bet back (draw, cancellation, or shutdown). */
    public void refund(Wager wager) {
        Map<UUID, Map<UUID, Double>> book = books.remove(wager);
        if (book != null) refundAll(book);
    }

    private void refundAll(Map<UUID, Map<UUID, Double>> book) {
        for (Map<UUID, Double> side : book.values()) {
            for (Map.Entry<UUID, Double> entry : side.entrySet()) {
                plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(entry.getKey()), entry.getValue());
                Player bettor = Bukkit.getPlayer(entry.getKey());
                if (bettor != null) {
                    msgs().send(bettor, "bet-refunded",
                            "%amount%", WagerManager.fmt(entry.getValue()));
                }
            }
        }
    }

    public void refundAllOpen() {
        for (Wager wager : new HashMap<>(books).keySet()) {
            refund(wager);
        }
        books.clear();
    }
}
