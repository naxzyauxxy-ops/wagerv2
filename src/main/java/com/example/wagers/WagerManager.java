package com.example.wagers;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class WagerManager {

    /** A pending challenge sent to a target player. */
    public record Request(UUID sender, UUID target, double amount, WagerMode mode, long createdAt) { }

    private final WagersPlugin plugin;

    private final Map<UUID, Request> pendingRequests = new HashMap<>();
    private final Map<UUID, Wager> activeWagers = new HashMap<>();
    /** Frozen players -> the exact spot they must stay on during countdown. */
    private final Map<UUID, Location> frozen = new HashMap<>();
    private final Map<UUID, Float> savedWalkSpeed = new HashMap<>();
    private final Map<UUID, Float> savedFlySpeed = new HashMap<>();
    private final Map<Wager, BukkitTask> countdownTasks = new HashMap<>();
    /** Players the plugin is currently teleporting itself (exempt from escape blocking). */
    private final Set<UUID> pluginTeleporting = new HashSet<>();

    public WagerManager(WagersPlugin plugin) {
        this.plugin = plugin;
    }

    private MessagesManager msgs() { return plugin.getMessages(); }

    /* ------------------------------------------------------------------ */
    /* Requests                                                            */
    /* ------------------------------------------------------------------ */

    public boolean sendRequest(Player sender, Player target, double amount, WagerMode mode) {
        if (isInWager(sender.getUniqueId())) {
            msgs().send(sender, "already-in-wager");
            return false;
        }
        if (isInWager(target.getUniqueId())) {
            msgs().send(sender, "target-in-wager", "%player%", target.getName());
            return false;
        }
        if (amount <= 0) {
            msgs().send(sender, "amount-positive");
            return false;
        }
        double min = plugin.getConfig().getDouble("min-wager", 1.0);
        double max = plugin.getConfig().getDouble("max-wager", 1000000.0);
        if (amount < min || amount > max) {
            msgs().send(sender, "amount-range", "%min%", fmt(min), "%max%", fmt(max));
            return false;
        }
        if (!plugin.getEconomy().has(sender, amount)) {
            msgs().send(sender, "not-enough-money", "%amount%", fmt(amount));
            return false;
        }

        pendingRequests.put(target.getUniqueId(),
                new Request(sender.getUniqueId(), target.getUniqueId(), amount, mode, System.currentTimeMillis()));

        msgs().send(sender, "request-sent",
                "%player%", target.getName(), "%amount%", fmt(amount), "%mode%", mode.getDisplay());
        msgs().send(target, "request-received-header");
        msgs().send(target, "request-received-body",
                "%player%", sender.getName(), "%mode%", mode.getDisplay(), "%amount%", fmt(amount));
        msgs().send(target, "request-received-footer", "%pot%", fmt(amount * 2));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);

        long expireTicks = plugin.getConfig().getLong("request-expire-seconds", 60) * 20L;
        UUID targetId = target.getUniqueId();
        Request placed = pendingRequests.get(targetId);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Request current = pendingRequests.get(targetId);
            if (current != null && current == placed) {
                pendingRequests.remove(targetId);
                Player s = Bukkit.getPlayer(placed.sender());
                if (s != null) msgs().send(s, "request-expired");
            }
        }, expireTicks);
        return true;
    }

    public Request getRequest(UUID target) {
        return pendingRequests.get(target);
    }

    public void denyRequest(Player target) {
        Request req = pendingRequests.remove(target.getUniqueId());
        if (req == null) {
            msgs().send(target, "no-pending-request");
            return;
        }
        msgs().send(target, "request-denied-target");
        Player sender = Bukkit.getPlayer(req.sender());
        if (sender != null) msgs().send(sender, "request-denied-sender", "%player%", target.getName());
    }

    /* ------------------------------------------------------------------ */
    /* Accept -> RTP -> countdown -> fight                                 */
    /* ------------------------------------------------------------------ */

    public void acceptRequest(Player target) {
        Request req = pendingRequests.remove(target.getUniqueId());
        if (req == null) {
            msgs().send(target, "no-pending-request");
            return;
        }
        Player sender = Bukkit.getPlayer(req.sender());
        if (sender == null || !sender.isOnline()) {
            msgs().send(target, "sender-offline");
            return;
        }
        if (isInWager(sender.getUniqueId()) || isInWager(target.getUniqueId())) {
            msgs().send(target, "one-already-fighting");
            return;
        }
        if (!plugin.getEconomy().has(sender, req.amount()) || !plugin.getEconomy().has(target, req.amount())) {
            msgs().send(target, "insufficient-at-accept");
            msgs().send(sender, "insufficient-cancelled");
            return;
        }

        beginFight(sender, target, req.amount(), req.mode());
    }

    /**
     * Set up and start a fight between two players. Used by accepted requests
     * and by the matchmaking queue. Takes the stakes, snapshots inventories,
     * moves both players to the mode's arena (or RTP), then counts down.
     */
    public void beginFight(Player sender, Player target, double amount, WagerMode mode) {
        plugin.getEconomy().withdrawPlayer(sender, amount);
        plugin.getEconomy().withdrawPlayer(target, amount);

        Wager wager = new Wager(sender.getUniqueId(), target.getUniqueId(), amount, mode);
        activeWagers.put(sender.getUniqueId(), wager);
        activeWagers.put(target.getUniqueId(), wager);

        snapshot(wager, sender);
        snapshot(wager, target);

        plugin.getQueueManager().remove(sender.getUniqueId());
        plugin.getQueueManager().remove(target.getUniqueId());

        Location loc1, loc2;
        ArenaManager.Arena arena = plugin.getArenaManager().getArena(mode);
        if (arena != null) {
            // Fixed arena for this mode (e.g. Sumo platform in the End)
            plugin.getArenaManager().ensureBuilt(mode);
            Location[] spawns = plugin.getArenaManager().duelSpawns(arena);
            loc1 = spawns[0];
            loc2 = spawns[1];
            wager.setLossY(arena.lossY());
            wager.setCenter(arena.center());
            // A little slack past the platform edge so knockback isn't cancelled
            wager.setBoundaryRadius(arena.radius + 8);
        } else {
            Location center = findRandomSafeLocation(sender.getWorld());
            if (center == null) {
                refundAndClear(wager, "no-safe-location");
                return;
            }
            int gap = plugin.getConfig().getInt("rtp.player-gap", 8);
            loc1 = center.clone();
            loc2 = shiftSafe(center, gap);
            loc1 = face(loc1, loc2);
            loc2 = face(loc2, loc1);
            wager.setCenter(center.clone());
            wager.setBoundaryRadius(plugin.getConfig().getDouble("fight-boundary-radius", 60));
        }

        safeTeleport(sender, loc1);
        safeTeleport(target, loc2);

        if (mode.usesKit()) {
            mode.applyKit(sender);
            mode.applyKit(target);
        }

        applyFreeze(sender, sender.getLocation());
        applyFreeze(target, target.getLocation());
        startCountdown(wager, sender, target);
    }

    private void startCountdown(Wager wager, Player p1, Player p2) {
        int seconds = plugin.getConfig().getInt("countdown-seconds", 5);
        broadcastFight(p1, p2, wager);

        BukkitTask task = new BukkitRunnable() {
            int remaining = seconds;

            @Override
            public void run() {
                Player a = Bukkit.getPlayer(wager.getPlayer1());
                Player b = Bukkit.getPlayer(wager.getPlayer2());
                if (a == null || b == null) {
                    cancel();
                    return;
                }
                if (remaining > 0) {
                    for (Player p : List.of(a, b)) {
                        p.sendTitle(
                                msgs().get("countdown-title", p, "%seconds%", String.valueOf(remaining)),
                                msgs().get("countdown-subtitle", p, "%pot%", fmt(wager.getPot())),
                                0, 25, 5);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                    remaining--;
                } else {
                    wager.setState(Wager.State.FIGHTING);
                    releaseFreeze(wager.getPlayer1());
                    releaseFreeze(wager.getPlayer2());
                    for (Player p : List.of(a, b)) {
                        p.sendTitle(
                                msgs().get("fight-title", p),
                                msgs().get("fight-subtitle", p, "%pot%", fmt(wager.getPot())),
                                0, 30, 10);
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
                    }
                    countdownTasks.remove(wager);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
        countdownTasks.put(wager, task);
    }

    /* ------------------------------------------------------------------ */
    /* Ending fights                                                       */
    /* ------------------------------------------------------------------ */

    public void endFight(Wager wager, UUID winnerId, UUID loserId, String reason) {
        if (wager.getState() == Wager.State.ENDED) return;
        wager.setState(Wager.State.ENDED);

        BukkitTask task = countdownTasks.remove(wager);
        if (task != null) task.cancel();

        releaseFreeze(wager.getPlayer1());
        releaseFreeze(wager.getPlayer2());
        activeWagers.remove(wager.getPlayer1());
        activeWagers.remove(wager.getPlayer2());

        OfflinePlayer winner = Bukkit.getOfflinePlayer(winnerId);
        plugin.getEconomy().depositPlayer(winner, wager.getPot());

        plugin.getBettingManager().settle(wager, winnerId, loserId);
        plugin.getSpectatorManager().releaseWatchersOf(wager.getPlayer1(), wager.getPlayer2());

        // Stats: winner nets +stake, loser nets -stake
        plugin.getPlayerData().recordWin(winnerId, wager.getAmount());
        plugin.getPlayerData().recordLoss(loserId, wager.getAmount());

        Player winnerOnline = Bukkit.getPlayer(winnerId);
        if (winnerOnline != null) {
            restore(wager, winnerOnline);
            winnerOnline.sendTitle(
                    msgs().get("victory-title", winnerOnline),
                    msgs().get("victory-subtitle", winnerOnline, "%pot%", fmt(wager.getPot())),
                    5, 60, 15);
            winnerOnline.playSound(winnerOnline.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Player loserOnline = Bukkit.getPlayer(loserId);
        if (loserOnline != null && !loserOnline.isDead()) {
            restore(wager, loserOnline);
        }

        String winnerName = winner.getName() == null ? "Someone" : winner.getName();
        String loserName = String.valueOf(Bukkit.getOfflinePlayer(loserId).getName());
        broadcast("win-broadcast", wager,
                "%winner%", winnerName, "%loser%", loserName,
                "%mode%", wager.getMode().getDisplay(),
                "%pot%", fmt(wager.getPot()), "%reason%", reason);
    }

    public void restore(Wager wager, Player p) {
        UUID id = p.getUniqueId();
        if (wager.getMode().usesKit() && wager.getSavedInventory(id) != null) {
            p.getInventory().clear();
            p.getInventory().setContents(wager.getSavedInventory(id));
            p.getInventory().setArmorContents(wager.getSavedArmor(id));
        }
        p.removePotionEffect(PotionEffectType.HUNGER);
        Location back = wager.getSavedLocation(id);
        if (back != null) safeTeleport(p, back);
        p.setFireTicks(0);
    }

    private void refundAndClear(Wager wager, String messageKey) {
        plugin.getBettingManager().refund(wager);
        plugin.getSpectatorManager().releaseWatchersOf(wager.getPlayer1(), wager.getPlayer2());
        plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(wager.getPlayer1()), wager.getAmount());
        plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(wager.getPlayer2()), wager.getAmount());
        activeWagers.remove(wager.getPlayer1());
        activeWagers.remove(wager.getPlayer2());
        releaseFreeze(wager.getPlayer1());
        releaseFreeze(wager.getPlayer2());
        for (UUID id : List.of(wager.getPlayer1(), wager.getPlayer2())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) msgs().send(p, messageKey);
        }
    }

    public void shutdown() {
        new HashSet<>(activeWagers.values()).forEach(w -> {
            if (w.getState() != Wager.State.ENDED) {
                w.setState(Wager.State.ENDED);
                plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(w.getPlayer1()), w.getAmount());
                plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(w.getPlayer2()), w.getAmount());
                for (UUID id : List.of(w.getPlayer1(), w.getPlayer2())) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) restore(w, p);
                }
            }
        });
        plugin.getBettingManager().refundAllOpen();
        plugin.getSpectatorManager().restoreAll();
        for (UUID id : new HashSet<>(frozen.keySet())) releaseFreeze(id);
        activeWagers.clear();
        frozen.clear();
        countdownTasks.values().forEach(BukkitTask::cancel);
        countdownTasks.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Broadcasts (respect the per-player message toggle)                  */
    /* ------------------------------------------------------------------ */

    private void broadcastFight(Player p1, Player p2, Wager wager) {
        broadcast("fight-broadcast", wager,
                "%player%", p1.getName(), "%opponent%", p2.getName(),
                "%mode%", wager.getMode().getDisplay(), "%pot%", fmt(wager.getPot()));
    }

    /** Broadcast a message; players who toggled messages OFF are skipped (fighters always get it). */
    private void broadcast(String key, Wager wager, String... replacements) {
        if (!plugin.getConfig().getBoolean("broadcast-fights", true)) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean participant = wager != null && wager.involves(online.getUniqueId());
            msgs().sendToggleable(online, participant, key, replacements);
        }
        Bukkit.getConsoleSender().sendMessage(msgs().get(key, null, replacements));
    }

    /* ------------------------------------------------------------------ */
    /* RTP helpers                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Find a random safe spot in the configured RTP world ONLY.
     * Never falls back to the player's current world - if the configured world
     * is missing, the wager is cancelled and refunded instead.
     */
    public Location findRandomSafeLocation(World ignored) {
        String worldName = plugin.getConfig().getString("rtp.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("RTP world '" + worldName + "' not found! Check rtp.world in config.yml.");
            return null;
        }

        int radius = plugin.getConfig().getInt("rtp.radius", 500);
        Location spawn = world.getSpawnLocation();

        // Pass 1 (attempts 0-24): only chunks already loaded in memory - zero cost.
        // Pass 2 (attempts 25-39): chunks already generated on disk - cheap load, no worldgen.
        // We NEVER generate new terrain here; that is what causes TPS drops.
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = spawn.getBlockX() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int z = spawn.getBlockZ() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int cx = x >> 4, cz = z >> 4;
            if (attempt < 25) {
                if (!world.isChunkLoaded(cx, cz)) continue;
            } else {
                if (!world.isChunkGenerated(cx, cz)) continue;
            }
            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y, z);
            Material type = ground.getType();
            if (type == Material.WATER || type == Material.LAVA || type == Material.CACTUS
                    || type == Material.MAGMA_BLOCK || !type.isSolid()) {
                continue;
            }
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    private Location shiftSafe(Location center, int gap) {
        World world = center.getWorld();
        int x = center.getBlockX() + gap;
        int z = center.getBlockZ();
        if (!world.isChunkGenerated(x >> 4, z >> 4)) {
            return center.clone().add(2, 0, 0);
        }
        int y = world.getHighestBlockYAt(x, z);
        Material ground = world.getBlockAt(x, y, z).getType();
        if (ground == Material.WATER || ground == Material.LAVA || !ground.isSolid()) {
            return center.clone().add(2, 0, 0);
        }
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    private Location face(Location from, Location to) {
        Location l = from.clone();
        l.setDirection(to.toVector().subtract(from.toVector()));
        return l;
    }

    private void snapshot(Wager wager, Player p) {
        wager.saveSnapshot(p.getUniqueId(),
                p.getInventory().getContents().clone(),
                p.getInventory().getArmorContents().clone(),
                p.getLocation().clone());
    }

    /* ------------------------------------------------------------------ */
    /* State queries                                                       */
    /* ------------------------------------------------------------------ */

    public boolean isInWager(UUID id) { return activeWagers.containsKey(id); }
    public Wager getWager(UUID id) { return activeWagers.get(id); }
    /**
     * Lock a player in place for the countdown. Belt and braces: anchor +
     * zero walk/fly speed + max slowness + jump suppression + cleared velocity.
     * Move cancellation alone lets sprint momentum slip through.
     */
    public void applyFreeze(Player p, Location anchor) {
        UUID id = p.getUniqueId();
        frozen.put(id, anchor.clone());

        savedWalkSpeed.putIfAbsent(id, p.getWalkSpeed());
        savedFlySpeed.putIfAbsent(id, p.getFlySpeed());

        p.setSprinting(false);
        p.setSneaking(false);
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0f);
        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 255, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, Integer.MAX_VALUE, 128, false, false, false));
    }

    /** Release a frozen player and give their normal movement back. */
    public void releaseFreeze(UUID id) {
        if (frozen.remove(id) == null) return;
        Player p = Bukkit.getPlayer(id);
        Float walk = savedWalkSpeed.remove(id);
        Float fly = savedFlySpeed.remove(id);
        if (p == null) return;
        p.setWalkSpeed(walk != null ? walk : 0.2f);
        p.setFlySpeed(fly != null ? fly : 0.1f);
        p.removePotionEffect(PotionEffectType.SLOW);
        p.removePotionEffect(PotionEffectType.JUMP);
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0f);
    }

    public boolean isFrozen(UUID id) { return frozen.containsKey(id); }

    /** The anchor a frozen player is pinned to, or null if not frozen. */
    public Location getFrozenLocation(UUID id) { return frozen.get(id); }

    public boolean isPluginTeleporting(UUID id) { return pluginTeleporting.contains(id); }

    /** Teleport a player without the escape-blocking listener cancelling it. */
    public void safeTeleport(Player p, Location to) {
        pluginTeleporting.add(p.getUniqueId());
        try {
            p.teleport(to);
        } finally {
            pluginTeleporting.remove(p.getUniqueId());
        }
    }

    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Q"};

    /** Format money: $1,000 -> $1K, $5,000,000 -> $5M, $2,500,000,000 -> $2.5B */
    public static String fmt(double amount) {
        WagersPlugin pl = WagersPlugin.get();
        boolean abbreviate = pl == null || pl.getConfig().getBoolean("abbreviate-money", true);
        boolean negative = amount < 0;
        double abs = Math.abs(amount);

        if (!abbreviate || abs < 1000) {
            String plain = abs == Math.floor(abs) ? String.valueOf((long) abs) : String.format("%.2f", abs);
            return (negative ? "-$" : "$") + plain;
        }

        int tier = 0;
        while (abs >= 1000 && tier < SUFFIXES.length - 1) {
            abs /= 1000;
            tier++;
        }
        // Rounding can push 999.99K up to 1000K - bump to the next tier
        if (Math.round(abs * 100) >= 100000 && tier < SUFFIXES.length - 1) {
            abs /= 1000;
            tier++;
        }
        // Up to 2 decimals, trimming trailing zeros: 5M, 1.5K, 2.25B
        String num = String.format("%.2f", abs)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
        return (negative ? "-$" : "$") + num + SUFFIXES[tier];
    }
}
