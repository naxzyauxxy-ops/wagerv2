package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.UUID;

public class FightListener implements Listener {

    private final WagersPlugin plugin;

    public FightListener(WagersPlugin plugin) {
        this.plugin = plugin;
    }

    private WagerManager wm() {
        return plugin.getWagerManager();
    }

    private EventManager em() {
        return plugin.getEventManager();
    }

    /* Freeze players during the pre-fight countdown (rotation still allowed). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        java.util.UUID moveId = event.getPlayer().getUniqueId();
        if (!wm().isFrozen(moveId) && !em().isFrozen(moveId)) return;
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getZ() != event.getTo().getZ()
                || event.getTo().getY() > event.getFrom().getY()) {
            event.setTo(event.getFrom().clone().setDirection(event.getTo().getDirection()));
        }
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
        if (!inWager && !inEvent) return;
        if (p.hasPermission("wagers.bypass-commands")) return;
        String cmd = event.getMessage().toLowerCase();
        if (cmd.startsWith("/wager")) return;
        event.setCancelled(true);
        plugin.getMessages().send(p, "no-commands");
    }
}
