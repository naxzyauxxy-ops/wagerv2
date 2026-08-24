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
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
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

    public record EventDef(String name, WagerMode mode, double prize, double entryFee,
                          MinigameManager.Game game) { }

    private final WagersPlugin plugin;
    private final List<EventDef> rotation = new ArrayList<>();

    private int index = 0;
    private State state = State.WAITING;
    private int secondsLeft;
    private int fightSecondsLeft;
    private int fightCountdown;
    /** Fall below this and you're out (platform arenas only). */
    private double eventLossY = Double.NEGATIVE_INFINITY;
    /** KOTH: player -> seconds held on the hill. */
    private final Map<UUID, Integer> hillTime = new HashMap<>();
    /** Towers: each player's generator block (the one they spawn on). */
    private final Map<UUID, Location> towers = new HashMap<>();
    private int generatorTick = 0;

    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new HashSet<>();
    private final Map<UUID, ItemStack[]> savedInv = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new HashMap<>();
    private final Map<UUID, Location> savedLoc = new HashMap<>();
    private double feePot = 0;

    private BukkitTask ticker;
    private BossBar bossBar;

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
            MinigameManager.Game game = MinigameManager.Game.match(
                    map.get("game") == null ? null : String.valueOf(map.get("game")));
            rotation.add(new EventDef(ChatColor.translateAlternateColorCodes('&', name),
                    mode, prize, fee, game));
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
        updateBossBar();
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
                    for (UUID id : alive) plugin.getWagerManager().releaseFreeze(id);
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
                tickKoth();
                tickGenerators();
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
        ArenaManager.Arena arena = plugin.getArenaManager().getArena(e.mode());
        MinigameManager.Map3D gameMap = null;
        if (e.game() != MinigameManager.Game.NONE) {
            gameMap = plugin.getMinigameManager().getMap(e.game());
        }
        Location center;
        if (gameMap != null) {
            // Spleef eats its own floor, so rebuild the map every round
            plugin.getMinigameManager().reset(e.game());
            center = gameMap.center();
        } else if (arena != null) {
            plugin.getArenaManager().ensureBuilt(e.mode());
            center = arena.center();
        } else {
            center = plugin.getWagerManager().findRandomSafeLocation(any.getWorld());
        }
        if (center == null) {
            refundFees();
            broadcastToggleable("event-cancelled", "%min%", String.valueOf(min));
            advance();
            return;
        }

        double borderRadius;
        if (gameMap != null) {
            borderRadius = gameMap.radius + 6;
            eventLossY = gameMap.lossY();
        } else if (arena != null) {
            borderRadius = arena.radius + 8;
            eventLossY = arena.lossY();
        } else {
            borderRadius = plugin.getConfig().getDouble("fight-boundary-radius", 60);
        }
        hillTime.clear();

        alive.clear();
        alive.addAll(participants);

        // Spread players in a circle around the center, facing inward
        int i = 0;
        double radius = Math.max(6, alive.size() * 2.0);
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            savedInv.put(id, p.getInventory().getContents().clone());
            savedArmor.put(id, p.getInventory().getArmorContents().clone());
            savedLoc.put(id, p.getLocation().clone());

            if (gameMap != null) {
                Location gameSpot = (e.game() == MinigameManager.Game.TOWERS)
                        ? plugin.getMinigameManager().towerSpawns(gameMap, alive.size()).get(i++)
                        : plugin.getMinigameManager().spawns(gameMap, alive.size()).get(i++);
                if (e.game() == MinigameManager.Game.TOWERS) {
                    towers.put(id, gameSpot.clone().add(0, -1, 0));
                }
                plugin.getWagerManager().safeTeleport(p, gameSpot);
                plugin.getWagerManager().applyFreeze(p, p.getLocation());
                plugin.getWagerManager().applyBorder(p, center, borderRadius);
                if (e.mode().usesKit()) e.mode().applyKit(p);
                plugin.getMinigameManager().applyKit(p, e.game());
                continue;
            }
            if (arena != null) {
                Location arenaSpot = plugin.getArenaManager()
                        .spreadSpawns(arena, alive.size()).get(i++);
                plugin.getWagerManager().safeTeleport(p, arenaSpot);
                plugin.getWagerManager().applyFreeze(p, p.getLocation());
                plugin.getWagerManager().applyBorder(p, center, borderRadius);
                if (e.mode().usesKit()) e.mode().applyKit(p);
                continue;
            }
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
            plugin.getWagerManager().safeTeleport(p, spot);
            plugin.getWagerManager().applyFreeze(p, p.getLocation());
            plugin.getWagerManager().applyBorder(p, center, borderRadius);
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
        plugin.getWagerManager().clearBorder(p);
        plugin.getWagerManager().releaseFreeze(id);
        EventDef e = current();
        // Minigames hand out their own gear (Spleef shovel etc), so their
        // items must be stripped too - not just kit-mode items.
        boolean wasKitted = e != null
                && (e.mode().usesKit() || e.game() != MinigameManager.Game.NONE);
        if (wasKitted) {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            if (savedInv.containsKey(id)) {
                p.getInventory().setContents(savedInv.get(id));
                p.getInventory().setArmorContents(savedArmor.get(id));
            }
        }
        p.removePotionEffect(org.bukkit.potion.PotionEffectType.HUNGER);
        Location back = savedLoc.get(id);
        if (back != null) plugin.getWagerManager().safeTeleport(p, back);
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
        hideBossBar();
        plugin.getSpectatorManager().releaseWatchersOf(participants.toArray(new UUID[0]));
        state = State.WAITING;
        eventLossY = Double.NEGATIVE_INFINITY;
        secondsLeft = plugin.getConfig().getInt("events.interval-seconds", 900);
        index = (index + 1) % Math.max(1, rotation.size());
        participants.clear();
        alive.clear();
        for (UUID id : alive) plugin.getWagerManager().releaseFreeze(id);
        hillTime.clear();
        towers.clear();
        generatorTick = 0;
        plugin.getMinigameManager().clearPlaced();
        savedInv.clear();
        savedArmor.clear();
        savedLoc.clear();
        feePot = 0;
        fightSecondsLeft = 0;
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        hideBossBar();
        for (UUID id : new HashSet<>(alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restoreOne(p);
        }
        refundFees();
    }

    /** Towers: every generator spits out items on its own timer. */
    private void tickGenerators() {
        EventDef e = current();
        if (e == null || e.game() != MinigameManager.Game.TOWERS) return;
        if (towers.isEmpty()) return;

        generatorTick++;
        if (generatorTick < plugin.getMinigameManager().generatorSeconds()) return;
        generatorTick = 0;

        for (UUID id : alive) {
            Location gen = towers.get(id);
            if (gen != null) plugin.getMinigameManager().runGenerator(gen);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Boss bar                                                            */
    /* ------------------------------------------------------------------ */

    /** Big bar at the top of the screen counting down to the event. */
    private void updateBossBar() {
        if (!plugin.getConfig().getBoolean("events.bossbar", true)) {
            hideBossBar();
            return;
        }
        EventDef e = current();
        if (e == null) {
            hideBossBar();
            return;
        }
        // Only show once the event is actually approaching
        int showAt = plugin.getConfig().getInt("events.bossbar-lead-seconds", 60);
        boolean show = switch (state) {
            case WAITING -> secondsLeft <= showAt;
            case JOINING, COUNTDOWN, RUNNING -> true;
        };
        if (!show) {
            hideBossBar();
            return;
        }

        if (bossBar == null) {
            bossBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        }

        String title;
        double progress;
        BarColor color;
        switch (state) {
            case WAITING -> {
                title = msgs().get("bossbar-waiting", null,
                        "%event%", e.name(), "%time%", formatTime(secondsLeft),
                        "%prize%", WagerManager.fmt(pot()));
                progress = clamp01(1.0 - (double) secondsLeft / Math.max(1, showAt));
                color = BarColor.YELLOW;
            }
            case JOINING -> {
                int total = Math.max(1, plugin.getConfig().getInt("events.join-seconds", 60));
                title = msgs().get("bossbar-joining", null,
                        "%event%", e.name(), "%time%", formatTime(secondsLeft),
                        "%players%", String.valueOf(participants.size()),
                        "%prize%", WagerManager.fmt(pot()));
                progress = clamp01((double) secondsLeft / total);
                color = BarColor.GREEN;
            }
            case COUNTDOWN -> {
                int total = Math.max(1, plugin.getConfig().getInt("countdown-seconds", 5));
                title = msgs().get("bossbar-countdown", null,
                        "%event%", e.name(), "%time%", formatTime(fightCountdown));
                progress = clamp01((double) fightCountdown / total);
                color = BarColor.RED;
            }
            default -> {
                int total = Math.max(1, plugin.getConfig().getInt("events.max-fight-seconds", 300));
                title = msgs().get("bossbar-running", null,
                        "%event%", e.name(), "%players%", String.valueOf(alive.size()),
                        "%time%", formatTime(fightSecondsLeft),
                        "%prize%", WagerManager.fmt(pot()));
                progress = clamp01((double) fightSecondsLeft / total);
                color = BarColor.RED;
            }
        }

        bossBar.setTitle(title);
        bossBar.setColor(color);
        bossBar.setProgress(progress);
        bossBar.setVisible(true);

        // Respect each player's message toggle; fighters always see it
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean eligible = alive.contains(p.getUniqueId())
                    || participants.contains(p.getUniqueId())
                    || plugin.getPlayerData().hasMessagesEnabled(p.getUniqueId());
            boolean shown = bossBar.getPlayers().contains(p);
            if (eligible && !shown) bossBar.addPlayer(p);
            else if (!eligible && shown) bossBar.removePlayer(p);
        }
    }

    private void hideBossBar() {
        if (bossBar == null) return;
        bossBar.removeAll();
        bossBar.setVisible(false);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** King of the Hill: hold the gold centre alone to build capture time. */
    private void tickKoth() {
        EventDef e = current();
        if (e == null || e.game() != MinigameManager.Game.KOTH) return;
        MinigameManager.Map3D map = plugin.getMinigameManager().getMap(MinigameManager.Game.KOTH);
        if (map == null) return;

        int hillR = plugin.getMinigameManager().hillRadius();
        List<UUID> onHill = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && map.insideHill(p.getLocation(), hillR)) onHill.add(id);
        }
        // Contested or empty hill: no progress
        if (onHill.size() != 1) return;

        UUID holder = onHill.get(0);
        int need = plugin.getMinigameManager().kothCaptureSeconds();
        int held = hillTime.merge(holder, 1, Integer::sum);

        Player p = Bukkit.getPlayer(holder);
        if (p != null && held < need) {
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    "\u00a76Capturing... \u00a7e" + held + "\u00a77/\u00a7e" + need));
        }
        if (held >= need && p != null) {
            // Capture complete - everyone else is out, this player wins
            for (UUID other : new HashSet<>(alive)) {
                if (other.equals(holder)) continue;
                Player op = Bukkit.getPlayer(other);
                if (op != null) restoreOne(op);
                alive.remove(other);
            }
            checkWin();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Placeholder + info accessors                                        */
    /* ------------------------------------------------------------------ */

    public State getState() { return state; }
    public double getLossY() { return eventLossY; }

    /** The minigame the current event runs, or NONE for a plain FFA. */
    public MinigameManager.Game currentGame() {
        EventDef e = current();
        return e == null ? MinigameManager.Game.NONE : e.game();
    }
    public boolean isParticipantAlive(UUID id) { return alive.contains(id); }
    public boolean isFrozen(UUID id) { return plugin.getWagerManager().isFrozen(id); }

    /** The anchor a frozen event fighter is pinned to, or null. */
    public Location getFrozenLocation(UUID id) { return plugin.getWagerManager().getFrozenLocation(id); }
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
     * Event timer placeholder (%wagers_event_animated%). Shows the countdown
     * only - no cycling between name/timer/join hint, so scoreboards stay
     * readable. Use %wagers_event_name% alongside it if you want the name.
     */
    public String animatedFrame() {
        if (current() == null) return "";
        return switch (state) {
            case WAITING -> "\u00a7e" + formatTime(secondsLeft);
            case JOINING -> "\u00a7a" + formatTime(secondsLeft);
            case COUNTDOWN -> "\u00a76" + formatTime(fightCountdown);
            case RUNNING -> "\u00a7c\u00a7lLIVE";
        };
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
