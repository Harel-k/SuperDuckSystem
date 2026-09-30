package com.qducks.superduckclientguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

final class EnforcementService {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final SuperDuckClientGuard plugin;
    private final StrikeStore strikes;
    private final WipeCoordinator wipeCoordinator;

    EnforcementService(SuperDuckClientGuard plugin, StrikeStore strikes, WipeCoordinator wipeCoordinator) {
        this.plugin = plugin;
        this.strikes = strikes;
        this.wipeCoordinator = wipeCoordinator;
    }

    void handleConfirmed(Player player, List<HackDefinition> detected) {
        if (detected.isEmpty()) return;

        List<HackDefinition> hackedClients = detected.stream()
                .filter(hack -> hack.policy() == DetectionPolicy.HACK_CLIENT)
                .toList();
        List<HackDefinition> freecam = detected.stream()
                .filter(hack -> hack.policy() == DetectionPolicy.FREECAM)
                .toList();
        List<HackDefinition> alertOnly = detected.stream()
                .filter(hack -> hack.policy() == DetectionPolicy.ALERT_ONLY)
                .toList();

        String all = names(detected);
        alertStaff("&c[ClientGuard] &fCONFIRMED &e" + player.getName() + " &7-> &c" + all);

        if (!plugin.getConfig().getBoolean("enforcement.enabled", false)) {
            alertStaff("&7[ClientGuard] Auto-enforcement is disabled; no punishment was applied.");
            return;
        }

        if (!hackedClients.isEmpty()) {
            handleHackedClient(player, hackedClients);
            return;
        }

        if (!freecam.isEmpty()) {
            handleFreecam(player);
        }

        if (!alertOnly.isEmpty()) {
            alertStaff("&e[ClientGuard] Alert-only detection for " + player.getName()
                    + ": " + names(alertOnly) + ". No automatic punishment.");
        }
    }

    private void handleHackedClient(Player player, List<HackDefinition> detected) {
        if (!plugin.getConfig().getBoolean("enforcement.hacked-client.destructive-wipe.enabled", false)) {
            plugin.getLogger().severe("Confirmed hacked client for " + player.getName()
                    + " (" + names(detected) + "), but destructive wipe is not armed. "
                    + "14-day punishment was intentionally blocked so ban and wipe cannot diverge.");
            alertStaff("&4[ClientGuard] &c14-day ban + wipe BLOCKED: destructive wipe is not armed yet.");
            return;
        }

        String playerName = player.getName();
        String banCommand = plugin.getConfig().getString(
                "enforcement.hacked-client.ban-command",
                "tempban {player} 14d Illegal client detected: {mods}"
        );

        wipeCoordinator.wipe(player, detected).whenComplete((outcome, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        String message = rootMessage(error);
                        plugin.getLogger().severe("ClientGuard wipe failed for " + playerName
                                + "; 14-day ban was NOT applied: " + message);
                        alertStaff("&4[ClientGuard] &cWipe failed for " + playerName
                                + "; 14-day ban was NOT applied. Check console.");
                        return;
                    }

                    if (outcome.hasWarnings()) {
                        plugin.getLogger().severe("ClientGuard wipe for " + playerName
                                + " completed with " + outcome.failedExternalCommands()
                                + " failed external wipe command(s).");
                        alertStaff("&4[ClientGuard] &cWARNING: " + playerName
                                + " was wiped, but an external wipe command failed. "
                                + "Check homes/external data manually.");
                    }

                    dispatch(banCommand, player, detected);
                    alertStaff("&c[ClientGuard] " + playerName
                            + " received the confirmed hacked-client 14-day sanction. "
                            + "Recovery snapshot=" + outcome.playerSnapshot().getName()
                            + ", SDS backup=" + outcome.databaseBackup().getName());
                })
        );
    }

    private void handleFreecam(Player player) {
        final int strike;
        try {
            strike = strikes.incrementFreecam(player.getUniqueId(), player.getName());
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Freecam strike could not be persisted for " + player.getName()
                    + "; punishment aborted: " + exception.getMessage());
            alertStaff("&4[ClientGuard] Freecam action aborted because strike persistence failed.");
            return;
        }

        if (strike <= 2) {
            String key = strike == 1 ? "first-warning" : "final-warning";
            String message = plugin.getConfig().getString(
                    "enforcement.freecam." + key,
                    strike == 1
                            ? "&cFreecam is not allowed. Remove it before reconnecting."
                            : "&4FINAL WARNING: &cRemove Freecam before reconnecting."
            );
            Component component = LEGACY.deserialize(message);
            player.sendMessage(component);
            alertStaff("&e[ClientGuard] " + player.getName() + " Freecam warning "
                    + strike + "/2.");

            if (plugin.getConfig().getBoolean("enforcement.freecam.disconnect-on-warning", true)) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) player.kick(component);
                }, 2L);
            }
            return;
        }

        String command = plugin.getConfig().getString(
                "enforcement.freecam.ban-command",
                "tempban {player} 3d Freecam after two warnings"
        );
        dispatch(command, player, List.of());
        alertStaff("&c[ClientGuard] " + player.getName()
                + " reached Freecam strike " + strike + "; 3-day ban command dispatched.");
    }

    private void dispatch(String template, Player player, List<HackDefinition> detected) {
        String command = template
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{mods}", names(detected));
        if (command.startsWith("/")) command = command.substring(1);

        boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (!accepted) {
            plugin.getLogger().severe("Configured punishment command was not accepted: " + command);
            alertStaff("&4[ClientGuard] Punishment command failed. Check console.");
        }
    }

    private String names(List<HackDefinition> definitions) {
        return definitions.stream()
                .map(HackDefinition::displayName)
                .collect(Collectors.joining(", "));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    void alertStaff(String legacyMessage) {
        Component message = LEGACY.deserialize(legacyMessage);
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("superduck.clientguard.alerts")) {
                online.sendMessage(message);
            }
        }
    }
}
