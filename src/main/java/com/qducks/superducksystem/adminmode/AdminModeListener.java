package com.qducks.superducksystem.adminmode;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AdminModeListener implements Listener {
    private static final Set<String> GAMEMODE_COMMANDS = Set.of(
            "gamemode", "gm", "gmc", "gms", "gma", "gmsp",
            "creative", "survival", "adventure", "spectator"
    );

    private final AdminModeService service;

    public AdminModeListener(AdminModeService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String root = commandRoot(event.getMessage());
        if (root.equals("abuse") || root.equals("adminmode")) return;

        if (service.isSwitching(player)) {
            event.setCancelled(true);
            player.sendMessage("§eYour Legit/Admin profile is still switching. Try again in a moment.");
            return;
        }

        if (service.isActive(player)) {
            if (service.isAdminTransferCommand(event.getMessage())) {
                event.setCancelled(true);
                player.sendMessage(service.transferBlockedMessage());
            }
            return;
        }

        if (service.isManaged(player) && (GAMEMODE_COMMANDS.contains(root) || service.isLegitBlockedCommand(event.getMessage()))) {
            event.setCancelled(true);
            player.sendMessage(service.blockedMessage());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onCommandList(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (!service.isManaged(player) || service.isActive(player)) return;

        Set<String> remove = new HashSet<>();
        for (String command : event.getCommands()) {
            String root = commandRoot("/" + command);
            if (GAMEMODE_COMMANDS.contains(root) || service.isLegitBlockedCommand("/" + command)) remove.add(command);
        }
        event.getCommands().removeAll(remove);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (!service.isManaged(player)) return;
        if (service.isActive(player) || service.isSwitching(player)) return;

        event.setCancelled(true);
        player.sendMessage("§a§lLEGIT MODE §8» §7Gamemode is locked. Enable §f/abuse on §7to use admin gamemodes.");
    }

    private String commandRoot(String message) {
        String body = message.startsWith("/") ? message.substring(1) : message;
        String root = body.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        return root;
    }
}
