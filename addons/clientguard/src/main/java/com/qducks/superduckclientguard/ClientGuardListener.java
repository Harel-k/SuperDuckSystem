package com.qducks.superduckclientguard;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class ClientGuardListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SuperDuckClientGuard plugin;
    private final ClientScanService scans;

    ClientGuardListener(SuperDuckClientGuard plugin, ClientScanService scans) {
        this.plugin = plugin;
        this.scans = scans;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("enabled", true)) return;
        if (!plugin.getConfig().getBoolean("scan.on-join", true)) return;

        Player player = event.getPlayer();
        if (player.hasPermission("superduck.clientguard.bypass")) return;

        long delay = Math.max(1L, plugin.getConfig().getLong("scan.join-delay-ticks", 100L));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) scans.startScan(player, "join");
        }, delay);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (!scans.isChecking(player.getUniqueId())) return;

        event.setCancelled(true);
        String[] lines = new String[4];
        for (int index = 0; index < 4; index++) {
            var component = event.line(index);
            lines[index] = component == null ? "" : PLAIN.serialize(component);
        }
        scans.handleResponse(player, lines);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scans.cancel(event.getPlayer().getUniqueId());
    }
}
