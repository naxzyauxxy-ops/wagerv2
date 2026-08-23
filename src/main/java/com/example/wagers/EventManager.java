package com.example.wagers;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Rotating scheduled events. Cycle: WAITING (countdown) -> JOINING (clickable
 * announcement, players /wager join) -> COUNTDOWN (frozen) -> RUNNING (FFA,
 * last one standing) -> next event in the rotation.
 */
public class EventManager {

    public enum State { WAITING, JOINING, COUNTDOWN, RUNNING }

    public record EventDef(String name, WagerMode mode, double prize, double entryFee) { }

    private final WagersPlugin plugin;
    private final List<EventDef> rotation = new ArrayList<>();

    private int index = 0;
    private State state = State.WAITING;
    private int secondsLeft;
    private int fightSecondsLeft;
    private int fightCountdown;

    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new HashSet<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Map<UUID, ItemStack[]> savedInv = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new HashMap<>();
    private final Map<UUID, Location> savedLoc = new HashMap<>();
    private double feePot = 0;

    private BukkitTask ticker;

    public EventManager(WagersPlugin plugin) {
        this.plugin = plugin;
        loadRotation();
        if (plugin.getConfig().getBoolean("events.enabled", true) && !rotation.isEmpty()) {
            secondsLeft = plugin.getConfig().getInt("events.interval-seconds", 900);
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }
    }

    private void loadRotation() {
        List<Map<?, ?>> list = plugin.getConfig().getMapList("events.rotation");
        for (Map<?, ?> map : list) {
            String name = String.valueOf(map.get("name"));
            WagerMode mode = WagerMode.match(String.valueOf(map.get("mode")));
            if (mode == null) mode = WagerMode.CLASSIC;
            double prize = map.get("prize") instanceof Number n ? n.doubleValue() : 0;
            double fee = map.get("entry-fee") instanceof Number n ? n.doubleValue() : 0;
            rotation.add(new EventDef(ChatColor.translateAlternateColorCodes('&', name), mode, prize, fee));
        }
        if (rotation.isEmpty()) {
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("events");
            if (sec != null) plugin.getLogger().warning("events.rotation is empty - events disabled.");
        }
    }

    private MessagesManager msgs() { return plugin.getMessages(); }

    public EventDef current() {
        return rotation.isEmpty() ? null : rotation.get(index % rotation.size());
    }

    /* ------------------------------------------------------------------ */
    /* Main 1-second tick                                                  */
    /* ------------------------------------------------------------------ */

    private void tick() {
        switch (state) {
            case WAITING -> {
                secondsLeft--;
                if (secondsLeft <= 0) openJoin();
            }
            case JOINING -> {
                secondsLeft--;
                if (secondsLeft == 30 || secondsLeft == 10) announceJoin(false);
                if (secondsLeft <= 0) tryStart();
            }
            case COUNTDOWN -> {
                fightCountdown--;
                if (fightCountdown > 0) {
                    for (UUID id : alive) {
                        Player p = Bukkit.getPlayer(id);
                        if (p == null) continue;
                        p.sendTitle(
                                msgs().get("countdown-title", p, "%seconds%", String.valueOf(fightCountdown)),
                                msgs().get("countdown-subtitle", p, "%pot%", WagerManager.fmt(pot())),
                                0, 25, 5);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                } else {
                    state = State.RUNNING;
                    frozen.clear();
                    fightSecondsLeft = plugin.getConfig().getInt("events.max-fight-seconds", 300);
                    for (UUID id : alive) {
                        Player p = Bukkit.getPlayer(id);
                        if (p == null) continue;
                        p.sendTitle(msgs().get("fight-title", p),
                                msgs().get("fight-subtitle", p, "%pot%", WagerManager.fmt(pot())), 0, 30, 10);
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
                    }
                }
            }
            case RUNNING -> {
                fightSecondsLeft--;
                if (fightSecondsLeft <= 0) endDraw();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Join phase                                                          */
    /* ------------------------------------------------------------------ */

    private void openJoin() {
        state = State.JOINING;
        secondsLeft = plugin.getConfig().getInt("events.join-seconds", 60);
        participants.clear();
        feePot = 0;
        announceJoin(true);
    }

    private void announceJoin(boolean full) {
        EventDef e = current();
        if (e == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.getPlayerData().hasMessagesEnabled(p.getUniqueId())) continue;
            if (full) {
                p.sendMessage(msgs().get("event-announce-header", p));
                p.sendMessage(msgs().get("event-announce-body", p,
                        "%event%", e.name(), "%mode%", e.mode().getDisplay(),
                        "%prize%", WagerManager.fmt(e.prize())));
            }
            p.sendMessage(msgs().get("event-announce-time", p, "%time%", formatTime(secondsLeft)));
            p.spigot().sendMessage(joinButton(p, e));
            if (full) p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
        }
    }

    private TextComponent joinButton(Player p, EventDef e) {
        String prefix = msgs().getPrefix();
        TextComponent button = new TextComponent(TextComponent.fromLegacyText(
                prefix + msgs().get("event-join-button", p)));
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/wagersplugin:wager join"));
        String hover = msgs().get("event-join-hover", p,
                "%event%", e.name(), "%prize%", WagerManager.fmt(e.prize()),
                "%fee%", WagerManager.fmt(e.entryFee()));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(hover))));
        return button;
    }

    public void join(Player p) {
        EventDef e = current();
        if (state != State.JOINING || e == null) {
            msgs().send(p, "event-not-joinable");
            return;
        }
        if (plugin.getWagerManager().isInWager(p.getUniqueId())) {
            msgs().send(p, "event-in-wager");
            return;
        }
        if (participants.contains(p.getUniqueId())) {
            msgs().send(p, "event-already-joined");
            return;
        }
        if (e.entryFee() > 0) {
            if (!plugin.getEconomy().has(p, e.entryFee())) {
                msgs().send(p, "event-fee-needed", "%fee%", WagerManager.fmt(e.entryFee()));
                return;
            }
            plugin.getEconomy().withdrawPlayer(p, e.entryFee());
            feePot += e.entryFee();
        }
        participants.add(p.getUniqueId());
        msgs().send(p, "event-joined", "%event%", e.name(),
                "%players%", String.valueOf(participants.size()));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.8f);
        broadcastToggleable("event-join-broadcast",
                "%player%", p.getName(), "%event%", e.name(),
                "%players%", String.valueOf(participants.size()));
    }

    public void leave(Player p) {
        EventDef e = current();
        if (state != State.JOINING || !participants.remove(p.getUniqueId())) {
            msgs().send(p, "event-not-in");
            return;
        }
        if (e != null && e.entryFee() > 0) {
            plugin.getEconomy().depositPlayer(p, e.entryFee());
            feePot -= e.entryFee();
        }
        msgs().send(p, "event-left");
    }

    /* ------------------------------------------------------------------ */
    /* Fight phase                                                         */
    /* ------------------------------------------------------------------ */

    private void tryStart() {
        EventDef e = current();
        int min = plugin.getConfig().getInt("events.min-players", 2);
        // Drop anyone who logged off during the join window
        participants.removeIf(id -> Bukkit.getPlayer(id) == null);

        if (e == null || participants.size() < min) {
            refundFees();
            broadcastToggleable("event-cancelled", "%min%", String.valueOf(min));
            advance();
            return;
        }

        Player any = Bukkit.getPlayer(participants.iterator().next());
        Location center = plugin.getWagerManager().findRandomSafeLocation(any.getWorld());
        if (center == null) {
            refundFees();
            broadcastToggleable("event-cancelled", "%min%", String.valueOf(min));
            advance();
            return;
        }

        alive.clear();
        alive.addAll(participants);
        frozen.addAll(alive);

        // Spread players in a circle around the center, facing inward
        int i = 0;
        double radius = Math.max(6, alive.size() * 2.0);
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            savedInv.put(id, p.getInventory().getContents().clone());
            savedArmor.put(id, p.getInventory().getArmorContents().clone());
            savedLoc.put(id, p.getLocation().clone());

            double angle = (2 * Math.PI / alive.size()) * i++;
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            Location spot;
            if (center.getWorld().isChunkGenerated(x >> 4, z >> 4)) {
                int y = center.getWorld().getHighestBlockYAt(x, z);
                spot = new Location(center.getWorld(), x + 0.5, y + 1, z + 0.5);
            } else {
                spot = center.clone().add((i % 3) - 1, 0, (i % 5) - 2);
            }
            spot.setDirection(center.toVector().subtract(spot.toVector()));
            p.teleport(spot);
            if (e.mode().usesKit()) e.mode().applyKit(p);
        }

        state = State.COUNTDOWN;
        fightCountdown = plugin.getConfig().getInt("countdown-seconds", 5) + 1;
        broadcastToggleable("event-started",
                "%event%", e.name(), "%players%", String.valueOf(alive.size()),
                "%prize%", WagerManager.fmt(pot()));
    }

    public void eliminate(Player p, String reason) {
        if (!alive.remove(p.getUniqueId())) return;
        EventDef e = current();
        broadcastToggleable("event-eliminated",
                "%player%", p.getName(), "%event%", e == null ? "" : e.name(),
                "%players%", String.valueOf(alive.size()));

        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player back = Bukkit.getPlayer(id);
            if (back != null && !back.isDead()) restoreOne(back);
        }, 2L);

        checkWin();
    }

    private void checkWin() {
        if (state != State.RUNNING && state != State.COUNTDOWN) return;
        if (alive.size() > 1) return;

        EventDef e = current();
        if (alive.size() == 1) {
            UUID winnerId = alive.iterator().next();
            Player winner = Bukkit.getPlayer(winnerId);
            plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(winnerId), pot());
            plugin.getPlayerData().recordWin(winnerId, pot());
            if (winner != null) {
                restoreOne(winner);
                winner.sendTitle(
                        msgs().get("event-winner-title", winner),
                        msgs().get("event-winner-subtitle", winner, "%prize%", WagerManager.fmt(pot())),
                        5, 60, 15);
                winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            String name = Bukkit.getOfflinePlayer(winnerId).getName();
            broadcastToggleable("event-winner",
                    "%player%", String.valueOf(name), "%event%", e == null ? "" : e.name(),
                    "%prize%", WagerManager.fmt(pot()));
        }
        advance();
    }

    private void endDraw() {
        EventDef e = current();
        for (UUID id : new HashSet<>(alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restoreOne(p);
        }
        refundFees();
        broadcastToggleable("event-draw", "%event%", e == null ? "" : e.name());
        advance();
    }

    private void restoreOne(Player p) {
        UUID id = p.getUniqueId();
        EventDef e = current();
        if (e != null && e.mode().usesKit() && savedInv.containsKey(id)) {
            p.getInventory().clear();
            p.getInventory().setContents(savedInv.get(id));
            p.getInventory().setArmorContents(savedArmor.get(id));
        }
        p.removePotionEffect(org.bukkit.potion.PotionEffectType.HUNGER);
        Location back = savedLoc.get(id);
        if (back != null) p.teleport(back);
        p.setFireTicks(0);
    }

    private void refundFees() {
        EventDef e = current();
        if (e == null || e.entryFee() <= 0) return;
        for (UUID id : participants) {
            plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(id), e.entryFee());
        }
        feePot = 0;
    }

    /** Move to the next event in the rotation and restart the waiting timer. */
    private void advance() {
        state = State.WAITING;
        secondsLeft = plugin.getConfig().getInt("events.interval-seconds", 900);
        index = (index + 1) % Math.max(1, rotation.size());
        participants.clear();
        alive.clear();
        frozen.clear();
        savedInv.clear();
        savedArmor.clear();
        savedLoc.clear();
        feePot = 0;
        fightSecondsLeft = 0;
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (UUID id : new HashSet<>(alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restoreOne(p);
        }
        refundFees();
    }

    /* ------------------------------------------------------------------ */
    /* Placeholder + info accessors                                        */
    /* ------------------------------------------------------------------ */

    public State getState() { return state; }
    public boolean isParticipantAlive(UUID id) { return alive.contains(id); }
    public boolean isFrozen(UUID id) { return frozen.contains(id); }
    public int getAliveCount() { return alive.size(); }
    public int getJoinedCount() { return participants.size(); }
    public double pot() { return (current() == null ? 0 : current().prize()) + feePot; }

    public String eventName() {
        EventDef e = current();
        return e == null ? "None" : e.name();
    }

    public String eventTime() {
        return switch (state) {
            case WAITING -> formatTime(secondsLeft);
            case JOINING -> formatTime(secondsLeft);
            case COUNTDOWN -> formatTime(fightCountdown);
            case RUNNING -> ChatColor.translateAlternateColorCodes('&',
                    plugin.getMessages().getRaw("event-status-running"));
        };
    }

    public String eventStatus(Player context) {
        return switch (state) {
            case WAITING -> msgs().get("event-status-waiting", context);
            case JOINING -> msgs().get("event-status-joining", context);
            case COUNTDOWN, RUNNING -> msgs().get("event-status-running", context);
        };
    }

    /**
     * Animated placeholder: cycles frames every `events.animation-frame-seconds`.
     * Frame 1: event name  |  Frame 2: timer  |  (Frame 3 while joinable: JOIN hint)
     */
    public String animatedFrame() {
        EventDef e = current();
        if (e == null) return "";
        int frameSecs = Math.max(1, plugin.getConfig().getInt("events.animation-frame-seconds", 2));
        int frames = (state == State.JOINING) ? 3 : 2;
        long frame = (System.currentTimeMillis() / (frameSecs * 1000L)) % frames;
        if (frame == 0) return e.name();
        if (frame == 1) return switch (state) {
            case WAITING -> "§7Next in §e" + formatTime(secondsLeft);
            case JOINING -> "§aJoin: §e" + formatTime(secondsLeft);
            case COUNTDOWN -> "§6Starting: §e" + formatTime(fightCountdown);
            case RUNNING -> "§c§lLIVE §7(" + alive.size() + " left)";
        };
        return "§a§l/wager join";
    }

    public static String formatTime(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private void broadcastToggleable(String key, String... replacements) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean participant = participants.contains(online.getUniqueId());
            msgs().sendToggleable(online, participant, key, replacements);
        }
        Bukkit.getConsoleSender().sendMessage(msgs().get(key, null, replacements));
    }
}
