package com.qducks.superducksystem.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class CombatListener implements Listener {
    private final CombatService combat;

    public CombatListener(CombatService combat) {
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        combat.tag(attacker, victim);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!combat.shouldBlockCommand(event.getPlayer(), event.getMessage())) return;
        event.setCancelled(true);
        combat.sendBlocked(event.getPlayer(), combat.commandLabel(event.getMessage()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!combat.isTagged(event.getPlayer())) return;
        event.setCancelled(true);
        combat.sendBlockedTeleport(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        combat.clear(event.getEntity(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (combat.isTagged(event.getPlayer())) {
            combat.punishCombatLog(event.getPlayer());
        }
        combat.clear(event.getPlayer(), false);
    }

    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
}
