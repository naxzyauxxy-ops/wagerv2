package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Lets players watch an ongoing wager fight in spectator mode. */
public class SpectatorManager {

    private record Snapshot(GameMode gameMode, Location location) { }

    private final WagersPlugin plugin;
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    /** spectator UUID -> the fighter they're following */
    private final Map<UUID, UUID> watching = new HashMap<>();

    public SpectatorManager(WagersPlugin plugin) {
        this.plugin = plugin;
    }

    private MessagesManager msgs() {
        return plugin.getMessages();
    }

    public boolean isSpectating(UUID id) {
        return watching.containsKey(id);
    }

    /** The fighter this spectator is following, or null. */
    public UUID getWatchedFighter(UUID spectatorId) {
        return watching.get(spectatorId);
    }

    public void spectate(Player viewer, Player target) {
        if (!plugin.getConfig().getBoolean("allow-spectating", true)) {
            msgs().send(viewer, "spectate-disabled");
            return;
        }
        if (plugin.getWagerManager().isInWager(viewer.getUniqueId())
                || plugin.getEventManager().isParticipantAlive(viewer.getUniqueId())) {
            msgs().send(viewer, "spectate-while-fighting");
            return;
        }
        boolean targetFighting = plugin.getWagerManager().isInWager(target.getUniqueId())
                || plugin.getEventManager().isParticipantAlive(target.getUniqueId());
        if (!targetFighting) {
            msgs().send(viewer, "spectate-not-fighting", "%player%", target.getName());
            return;
        }
        if (viewer.equals(target)) {
            msgs().send(viewer, "spectate-self");
            return;
        }

        if (!snapshots.containsKey(viewer.getUniqueId())) {
            snapshots.put(viewer.getUniqueId(), new Snapshot(viewer.getGameMode(), viewer.getLocation().clone()));
        }
        watching.put(viewer.getUniqueId(), target.getUniqueId());

        viewer.setGameMode(GameMode.SPECTATOR);
        viewer.teleport(target.getLocation());

        Wager fight = plugin.getWagerManager().getWager(target.getUniqueId());
        if (fight != null && fight.getCenter() != null) {
            plugin.getWagerManager().applyBorder(viewer, fight.getCenter(), fight.getBoundaryRadius());
        } else {
            plugin.getWagerManager().applyBorder(viewer, target.getLocation(),
                    plugin.getConfig().getDouble("spectator-leash-radius", 25));
        }
        msgs().send(viewer, "spectate-started", "%player%", target.getName());
    }

    /** Stop spectating and put the player back exactly where they were. */
    public void stop(Player viewer, boolean notify) {
        UUID id = viewer.getUniqueId();
        if (watching.remove(id) == null) {
            if (notify) msgs().send(viewer, "spectate-not-spectating");
            return;
        }
        plugin.getWagerManager().clearBorder(viewer);
        Snapshot snap = snapshots.remove(id);
        if (snap != null) {
            viewer.setGameMode(snap.gameMode());
            viewer.teleport(snap.location());
        } else {
            viewer.setGameMode(GameMode.SURVIVAL);
        }
        if (notify) msgs().send(viewer, "spectate-stopped");
    }

    /** Called when a fight ends: kick everyone watching those fighters back to normal. */
    public void releaseWatchersOf(UUID... fighters) {
        Set<UUID> targets = new HashSet<>();
        for (UUID f : fighters) targets.add(f);

        for (UUID viewerId : new HashSet<>(watching.keySet())) {
            if (!targets.contains(watching.get(viewerId))) continue;
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                stop(viewer, false);
                msgs().send(viewer, "spectate-fight-over");
            } else {
                watching.remove(viewerId);
                snapshots.remove(viewerId);
            }
        }
    }

    /** On quit/shutdown, make sure nobody is left stuck in spectator mode. */
    public void restoreAll() {
        for (UUID viewerId : new HashSet<>(watching.keySet())) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) stop(viewer, false);
            else {
                watching.remove(viewerId);
                snapshots.remove(viewerId);
            }
        }
    }

    public void handleQuit(Player viewer) {
        UUID id = viewer.getUniqueId();
        if (!watching.containsKey(id)) return;
        stop(viewer, false);
    }
}
