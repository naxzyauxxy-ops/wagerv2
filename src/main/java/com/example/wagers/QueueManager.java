package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Matchmaking queue. Players queue for a mode + stake; the first two waiting
 * on the same mode and the same amount are matched into a fight automatically.
 */
public class QueueManager {

    public record Entry(UUID player, WagerMode mode, double amount, long since) { }

    private final WagersPlugin plugin;
    private final List<Entry> queue = new ArrayList<>();

    public QueueManager(WagersPlugin plugin) {
        this.plugin = plugin;
    }

    private MessagesManager msgs() {
        return plugin.getMessages();
    }

    public boolean isQueued(UUID id) {
        return queue.stream().anyMatch(e -> e.player().equals(id));
    }

    public Entry getEntry(UUID id) {
        return queue.stream().filter(e -> e.player().equals(id)).findFirst().orElse(null);
    }

    public int size() {
        return queue.size();
    }

    /** Number of players waiting for the same mode + stake. */
    public int sizeFor(WagerMode mode, double amount) {
        return (int) queue.stream()
                .filter(e -> e.mode() == mode && e.amount() == amount)
                .count();
    }

    public void join(Player player, WagerMode mode, double amount) {
        UUID id = player.getUniqueId();

        if (plugin.getWagerManager().isInWager(id)) {
            msgs().send(player, "already-in-wager");
            return;
        }
        if (isQueued(id)) {
            msgs().send(player, "queue-already");
            return;
        }
        double min = plugin.getConfig().getDouble("min-wager", 1.0);
        double max = plugin.getConfig().getDouble("max-wager", 1000000.0);
        if (amount < min || amount > max) {
            msgs().send(player, "amount-range",
                    "%min%", WagerManager.fmt(min), "%max%", WagerManager.fmt(max));
            return;
        }
        if (!plugin.getEconomy().has(player, amount)) {
            msgs().send(player, "not-enough-money", "%amount%", WagerManager.fmt(amount));
            return;
        }

        // Look for an opponent already waiting on the same mode + stake
        Iterator<Entry> it = queue.iterator();
        while (it.hasNext()) {
            Entry other = it.next();
            if (other.mode() != mode || other.amount() != amount) continue;

            Player opponent = Bukkit.getPlayer(other.player());
            if (opponent == null || !opponent.isOnline()) {
                it.remove();
                continue;
            }
            if (plugin.getWagerManager().isInWager(opponent.getUniqueId())) {
                it.remove();
                continue;
            }
            if (!plugin.getEconomy().has(opponent, amount)) {
                it.remove();
                msgs().send(opponent, "queue-removed-funds");
                continue;
            }

            // Match found
            it.remove();
            msgs().send(player, "queue-matched", "%player%", opponent.getName());
            msgs().send(opponent, "queue-matched", "%player%", player.getName());
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.8f);
            opponent.playSound(opponent.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.8f);
            plugin.getWagerManager().beginFight(opponent, player, amount, mode);
            return;
        }

        queue.add(new Entry(id, mode, amount, System.currentTimeMillis()));
        msgs().send(player, "queue-joined",
                "%mode%", mode.getDisplay(),
                "%amount%", WagerManager.fmt(amount),
                "%queued%", String.valueOf(sizeFor(mode, amount)));
    }

    public void leave(Player player) {
        if (!queue.removeIf(e -> e.player().equals(player.getUniqueId()))) {
            msgs().send(player, "queue-not-in");
            return;
        }
        msgs().send(player, "queue-left");
    }

    public void status(Player player) {
        Entry entry = getEntry(player.getUniqueId());
        msgs().send(player, "queue-header");
        if (entry == null) {
            msgs().send(player, "queue-status-none");
        } else {
            long waited = (System.currentTimeMillis() - entry.since()) / 1000L;
            msgs().send(player, "queue-status-in",
                    "%mode%", entry.mode().getDisplay(),
                    "%amount%", WagerManager.fmt(entry.amount()),
                    "%time%", EventManager.formatTime((int) waited));
        }
        msgs().send(player, "queue-status-total", "%queued%", String.valueOf(queue.size()));
    }

    public void remove(UUID id) {
        queue.removeIf(e -> e.player().equals(id));
    }

    public void clear() {
        queue.clear();
    }
}
