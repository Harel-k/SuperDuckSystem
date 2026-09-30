package com.qducks.clientguard.policy;

import com.qducks.clientguard.SuperDuckClientGuard;
import com.qducks.clientguard.detect.HackDefinition;
import com.qducks.clientguard.sanction.SanctionService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class PolicyEngine {
    private final SuperDuckClientGuard plugin;
    private final StrikeStore strikes;
    private final SanctionService sanctions;

    public PolicyEngine(SuperDuckClientGuard plugin, StrikeStore strikes, SanctionService sanctions) {
        this.plugin = plugin;
        this.strikes = strikes;
        this.sanctions = sanctions;
    }

    public void handleConfirmed(Player player, List<HackDefinition> detected) {
        if (detected.isEmpty()) return;

        Set<String> ids = detected.stream().map(HackDefinition::id).collect(Collectors.toSet());
        Set<String> hard = new HashSet<>(plugin.getConfig().getStringList("policy.hard-cheats"));
        Set<String> freecam = new HashSet<>(plugin.getConfig().getStringList("policy.freecam"));

        List<HackDefinition> hardHits = detected.stream().filter(h -> hard.contains(h.id())).toList();
        if (!hardHits.isEmpty()) {
            sanctions.handleConfirmedHardCheat(player, hardHits);
            return;
        }

        if (ids.stream().anyMatch(freecam::contains)) {
            handleFreecam(player);
            return;
        }

        String mods = detected.stream().map(HackDefinition::displayName).collect(Collectors.joining(", "));
        alert("<yellow>ClientGuard review:</yellow> <white>" + player.getName()
                + "</white> <gray>confirmed:</gray> <yellow>" + mods + "</yellow>");
    }

    private void handleFreecam(Player player) {
        int strike = strikes.incrementFreecam(player.getUniqueId());
        alert("<yellow>Freecam confirmed:</yellow> <white>" + player.getName()
                + "</white> <gray>(strike " + strike + ")</gray>");

        if (!plugin.getConfig().getBoolean("enforcement.enabled", false)) return;

        int warnings = Math.max(1, plugin.getConfig().getInt("enforcement.freecam-warnings-before-ban", 2));
        if (strike <= warnings) {
            String key = strike == 1 ? "enforcement.freecam-warning-1" : "enforcement.freecam-warning-2";
            String message = plugin.getConfig().getString(key,
                    strike == 1
                            ? "Freecam is not allowed. Remove it before reconnecting."
                            : "FINAL WARNING: remove Freecam before reconnecting.");
            player.kick(Component.text(message));
            return;
        }

        String command = plugin.getConfig().getString(
                "enforcement.freecam-tempban-command",
                "tempban {player} 3d Freecam is not allowed on QDucks SMP");
        dispatch(command.replace("{player}", player.getName()));
    }

    public int freecamStrikes(Player player) {
        return strikes.getFreecamStrikes(player.getUniqueId());
    }

    public void forgive(Player player) {
        strikes.clear(player.getUniqueId());
    }

    private void dispatch(String command) {
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), normalized);
    }

    private void alert(String miniMessage) {
        plugin.getLogger().info(miniMessage.replaceAll("<[^>]+>", ""));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("superduck.clientguard.alerts")) {
                online.sendRichMessage("<dark_aqua>[ClientGuard]</dark_aqua> " + miniMessage);
            }
        }
    }
}
