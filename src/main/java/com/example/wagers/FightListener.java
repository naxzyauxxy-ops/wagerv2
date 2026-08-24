package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FightListener implements Listener {

    private final WagersPlugin plugin;
    /** Rate-limits boundary warnings so players aren't spammed every tick. */
    private final Map<UUID, Long> lastBoundaryWarn = new HashMap<>();

    public FightListener(WagersPlugin plugin) {
        this.plugin = plugin;
    }

    private WagerManager wm() {
        return plugin.getWagerManager();
    }

    private EventManager em() {
        return plugin.getEventManager();
    }

    /**
     * Freeze players during the pre-fight countdown. Pins them to the exact
     * spot they spawned on (looking around is still allowed) - comparing
     * from/to let players slide with sprint momentum, so we anchor instead.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        UUID moveId = event.getPlayer().getUniqueId();

        Location anchor = wm().getFrozenLocation(moveId);
        if (anchor == null) anchor = em().getFrozenLocation(moveId);
        if (anchor == null) return;
        if (event.getTo() == null) return;

        Location pinned = anchor.clone();
        pinned.setYaw(event.getTo().getYaw());
        pinned.setPitch(event.getTo().getPitch());
        event.setTo(pinned);
    }

    /* Only the two participants can hurt each other; nobody hurts them during countdown. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker == null) return;

        Wager victimWager = wm().getWager(victim.getUniqueId());
        Wager attackerWager = wm().getWager(attacker.getUniqueId());

        // Countdown = total protection
        if (victimWager != null && victimWager.getState() == Wager.State.COUNTDOWN) {
            event.setCancelled(true);
            return;
        }
        // Outsiders can't hit fighters, fighters can't hit outsiders
        if (victimWager != null && !victimWager.involves(attacker.getUniqueId())) {
            event.setCancelled(true);
            plugin.getMessages().send(attacker, "target-protected");
            return;
        }
        if (attackerWager != null && !attackerWager.involves(victim.getUniqueId())) {
            event.setCancelled(true);
            plugin.getMessages().send(attacker, "only-opponent");
            return;
        }

        // Event rules: alive participants only fight each other, and only once RUNNING
        boolean victimInEvent = em().isParticipantAlive(victim.getUniqueId());
        boolean attackerInEvent = em().isParticipantAlive(attacker.getUniqueId());
        if (victimInEvent || attackerInEvent) {
            if (em().getState() == EventManager.State.COUNTDOWN) {
                event.setCancelled(true);
                return;
            }
            if (victimInEvent != attackerInEvent) {
                event.setCancelled(true);
                plugin.getMessages().send(attacker,
                        victimInEvent ? "target-protected" : "only-opponent");
            }
        }
    }

    /* No damage at all during countdown (fall, fire, etc.) */
    @EventHandler(ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        Wager w = wm().getWager(p.getUniqueId());
        if (w != null && w.getState() == Wager.State.COUNTDOWN) {
            event.setCancelled(true);
            return;
        }
        if (em().isParticipantAlive(p.getUniqueId()) && em().getState() == EventManager.State.COUNTDOWN) {
            event.setCancelled(true);
        }
    }

    /** Knockback and explosions must not shove a frozen player off their spot. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenVelocity(PlayerVelocityEvent event) {
        if (!wm().isFrozen(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
    }

    /* Hardcore mode: no natural regen. */
    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        Wager w = wm().getWager(p.getUniqueId());
        if (w != null && w.getMode() == WagerMode.HARDCORE
                && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true);
        }
    }

    /* Death = loss. Keep inventory in kit modes so nothing dupes/drops; we restore snapshots. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();

        if (em().isParticipantAlive(dead.getUniqueId())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            em().eliminate(dead, "killed");
            return;
        }

        Wager w = wm().getWager(dead.getUniqueId());
        if (w == null) return;

        if (w.getMode().usesKit()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        wm().endFight(w, w.getOpponent(dead.getUniqueId()), dead.getUniqueId(), "killed");

        // Restore their real inventory + send them back once they respawn
        UUID deadId = dead.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(deadId);
            if (p != null && !p.isDead()) {
                wm().restore(w, p);
            }
        }, 2L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // Nothing extra needed; restore is scheduled from onDeath.
    }

    /* Quitting mid-wager = automatic loss (countdown or fight). */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();

        if (em().isParticipantAlive(quitter.getUniqueId())) {
            em().eliminate(quitter, "logged out");
        }

        Wager w = wm().getWager(quitter.getUniqueId());
        if (w == null) return;
        wm().restore(w, quitter); // give their stuff/location back before they're gone
        wm().endFight(w, w.getOpponent(quitter.getUniqueId()), quitter.getUniqueId(), "logged out");
    }

    /* Block commands during a fight except /wager. */
    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        boolean inWager = wm().isInWager(p.getUniqueId());
        boolean inEvent = em().isParticipantAlive(p.getUniqueId());
        boolean spectating = plugin.getSpectatorManager().isSpectating(p.getUniqueId());
        if (!inWager && !inEvent && !spectating) return;
        if (p.hasPermission("wagers.bypass-commands")) return;
        String cmd = event.getMessage().toLowerCase();
        if (cmd.startsWith("/wager")) return;
        event.setCancelled(true);
        plugin.getMessages().send(p, "no-commands");
    }

    /* ------------------------------------------------------------------ */
    /* Arena ring-outs + escape prevention                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Should pearls / chorus / elytra / teleports be blocked for this player
     * right now? Mode is set by block-escape-items in config.yml:
     *   countdown - blocked only while frozen pre-fight, free once FIGHT! shows (default)
     *   always    - blocked for the whole fight
     *   never     - never blocked
     */
    private boolean blockEscapes(Player p) {
        String mode = plugin.getConfig().getString("block-escape-items", "countdown").toLowerCase();
        if (mode.equals("never") || mode.equals("false")) return false;
        // Legacy configs stored this as a boolean; "true" now means countdown-only
        if (mode.equals("true")) mode = "countdown";

        Wager w = wm().getWager(p.getUniqueId());
        boolean inEvent = em().isParticipantAlive(p.getUniqueId());
        if (w == null && !inEvent) return false;

        if (mode.equals("always")) return true;

        // "countdown": only while the pre-fight countdown is still running
        boolean wagerCountdown = w != null && w.getState() == Wager.State.COUNTDOWN;
        boolean eventCountdown = inEvent && em().getState() == EventManager.State.COUNTDOWN;
        return wagerCountdown || eventCountdown;
    }

    /** Fall off a platform arena and you lose the fight. */
    @EventHandler(ignoreCancelled = true)
    public void onRingOut(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Wager w = wm().getWager(p.getUniqueId());
        if (w == null || w.getState() != Wager.State.FIGHTING) return;
        if (event.getTo() == null) return;
        if (event.getTo().getY() >= w.getLossY()) return;

        wm().endFight(w, w.getOpponent(p.getUniqueId()), p.getUniqueId(), "knocked off");
    }

    /** No pearling, chorus-fruiting, or /tp-ing out of a fight. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();

        // Spectators may never teleport themselves out of the fight
        if (plugin.getSpectatorManager().isSpectating(p.getUniqueId())
                && !wm().isPluginTeleporting(p.getUniqueId())) {
            switch (event.getCause()) {
                case COMMAND, PLUGIN, ENDER_PEARL, CHORUS_FRUIT -> {
                    event.setCancelled(true);
                    plugin.getMessages().send(p, "spectate-no-teleport");
                    return;
                }
                default -> { return; }
            }
        }

        if (!blockEscapes(p)) return;

        switch (event.getCause()) {
            case ENDER_PEARL, CHORUS_FRUIT, COMMAND, PLUGIN, SPECTATE -> {
                // The plugin's own teleports are exempt
                if (wm().isPluginTeleporting(p.getUniqueId())) return;
                event.setCancelled(true);
                plugin.getMessages().send(p, "no-teleporting");
            }
            default -> { }
        }
    }

    /** Cancel the pearl at throw time so it isn't even wasted. */
    @EventHandler(ignoreCancelled = true)
    public void onPearlThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player p)) return;
        if (!blockEscapes(p)) return;
        event.setCancelled(true);
        plugin.getMessages().send(p, "no-pearls");
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player p = event.getPlayer();
        if (!blockEscapes(p)) return;
        if (event.getItem().getType() != Material.CHORUS_FRUIT) return;
        event.setCancelled(true);
        plugin.getMessages().send(p, "no-pearls");
    }

    /** No flying away mid-fight. */
    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!blockEscapes(p)) return;
        if (!event.isGliding()) return;
        event.setCancelled(true);
        plugin.getMessages().send(p, "no-elytra");
    }

    /** Spectators leaving mid-fight shouldn't stay stuck in spectator mode. */
    @EventHandler
    public void onSpectatorQuit(PlayerQuitEvent event) {
        plugin.getSpectatorManager().handleQuit(event.getPlayer());
        plugin.getQueueManager().remove(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /* Perimeter: fighters can't run off, spectators can't wander          */
    /* ------------------------------------------------------------------ */

    /** Warn a player at most once every 2 seconds. */
    private void warnBoundary(Player p, String key) {
        long now = System.currentTimeMillis();
        Long last = lastBoundaryWarn.get(p.getUniqueId());
        if (last != null && now - last < 2000L) return;
        lastBoundaryWarn.put(p.getUniqueId(), now);
        plugin.getMessages().send(p, key);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
    }

    /**
     * Fighters are confined to a radius around the fight centre, and spectators
     * are leashed to the fighter they're watching. This stops spectator mode
     * being used to fly around and scout bases.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPerimeter(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();

        // --- Spectators: leashed to their fighter ---
        UUID watchedId = plugin.getSpectatorManager().getWatchedFighter(id);
        if (watchedId != null) {
            Player watched = Bukkit.getPlayer(watchedId);
            if (watched == null) {
                plugin.getSpectatorManager().stop(p, true);
                return;
            }
            double leash = plugin.getConfig().getDouble("spectator-leash-radius", 25);
            if (!event.getTo().getWorld().equals(watched.getWorld())
                    || event.getTo().distanceSquared(watched.getLocation()) > leash * leash) {
                event.setTo(event.getFrom());
                plugin.getWagerManager().safeTeleport(p, watched.getLocation());
                warnBoundary(p, "spectate-leashed");
            }
            return;
        }

        // --- Fighters: confined to the arena / fight area ---
        Wager w = wm().getWager(id);
        if (w == null || w.getCenter() == null || w.getBoundaryRadius() <= 0) return;
        if (w.getState() == Wager.State.ENDED) return;

        Location center = w.getCenter();
        if (!event.getTo().getWorld().equals(center.getWorld())) {
            event.setTo(event.getFrom());
            return;
        }
        double r = w.getBoundaryRadius();
        // Horizontal distance only, so falling off a platform still counts as a ring-out
        double dx = event.getTo().getX() - center.getX();
        double dz = event.getTo().getZ() - center.getZ();
        if (dx * dx + dz * dz <= r * r) return;

        event.setTo(event.getFrom());
        warnBoundary(p, "boundary-reached");
    }
}
