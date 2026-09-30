package com.qducks.clientguard.sanction;

import com.qducks.clientguard.SuperDuckClientGuard;
import com.qducks.clientguard.detect.HackDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class SanctionService {
    private final SuperDuckClientGuard plugin;
    private final ProgressSnapshotService snapshots;
    private final ProgressWipeService wipe;
    private final DuckyPvpAdapter duckyPvp;
    private final HomeStateAdapter homes;
    private final SanctionLock lock;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public SanctionService(
            SuperDuckClientGuard plugin,
            ProgressSnapshotService snapshots,
            ProgressWipeService wipe,
            DuckyPvpAdapter duckyPvp,
            HomeStateAdapter homes,
            SanctionLock lock
    ) {
        this.plugin = plugin;
        this.snapshots = snapshots;
        this.wipe = wipe;
        this.duckyPvp = duckyPvp;
        this.homes = homes;
        this.lock = lock;
    }

    public List<String> blockers() {
        List<String> blockers = new ArrayList<>();
        if (!duckyPvp.ready()) blockers.add(duckyPvp.blocker());
        if (!homes.ready()) blockers.add(homes.blocker());

        String command = plugin.getConfig().getString("enforcement.hard-cheat-tempban-command", "").trim();
        if (command.isEmpty()) {
            blockers.add("No hard-cheat tempban command is configured");
        } else {
            String root = command.startsWith("/") ? command.substring(1) : command;
            int space = root.indexOf(' ');
            if (space >= 0) root = root.substring(0, space);
            if (Bukkit.getPluginCommand(root) == null) {
                blockers.add("The configured tempban command '/" + root + "' is not registered");
            }
        }
        return blockers;
    }

    public boolean ready() {
        return blockers().isEmpty();
    }

    public void handleConfirmedHardCheat(Player player, List<HackDefinition> detections) {
        String mods = detections.stream()
                .map(HackDefinition::displayName)
                .collect(Collectors.joining(", "));

        if (!plugin.getConfig().getBoolean("enforcement.enabled", false)) {
            alert("<red><bold>CONFIRMED CLIENT CHEAT</bold></red> <yellow>" + player.getName()
                    + "</yellow> <gray>-></gray> <red>" + mods
                    + "</red> <gray>(monitor mode; no sanction)</gray>");
            return;
        }

        List<String> blockers = blockers();
        if (!blockers.isEmpty()) {
            alert("<red><bold>HARD SANCTION BLOCKED</bold></red> <yellow>" + player.getName()
                    + "</yellow> <gray>was confirmed for</gray> <red>" + mods
                    + "</red><gray>, but safety prerequisites are not ready: "
                    + escape(String.join("; ", blockers)) + "</gray>");
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!active.add(uuid)) return;

        lock.lock(player);
        alert("<gold>Preparing protected sanction snapshot for <yellow>" + player.getName()
                + "</yellow> before any wipe.</gold>");

        final String duckyBackup;
        final HomeStateAdapter.HomeSnapshot homeSnapshot;
        try {
            duckyBackup = duckyPvp.exportBackup(uuid);
            homeSnapshot = homes.capture(player);
        } catch (Throwable error) {
            failBeforeWipe(player, "could not snapshot external state: " + rootMessage(error));
            return;
        }

        snapshots.create(player, detections, duckyBackup, homeSnapshot).whenComplete((snapshot, snapshotError) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (snapshotError != null) {
                        failBeforeWipe(player, "snapshot failed: " + rootMessage(snapshotError));
                        return;
                    }
                    continueAfterSnapshot(player, detections, snapshot, homeSnapshot);
                })
        );
    }

    private void continueAfterSnapshot(Player player, List<HackDefinition> detections, File snapshot,
                                       HomeStateAdapter.HomeSnapshot homeSnapshot) {
        UUID uuid = player.getUniqueId();

        if (!player.isOnline()) {
            failBeforeWipe(player, "player disconnected before wipe; snapshot kept at " + snapshot.getAbsolutePath());
            return;
        }

        if (!duckyPvp.discardBackup(uuid)) {
            failBeforeWipe(player, "DuckyPVP backup could not be safely discarded; snapshot kept");
            return;
        }

        if (!homes.wipe(player, homeSnapshot)) {
            failAfterSnapshot(player, snapshot, "homes could not be fully wiped; no SDS wipe was attempted");
            return;
        }

        wipe.prepare(uuid);
        wipe.wipeSds(uuid).whenComplete((ignored, wipeError) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (wipeError != null) {
                        failAfterSnapshot(player, snapshot, "SDS wipe rolled back: " + rootMessage(wipeError));
                        return;
                    }

                    try {
                        wipe.wipeMinecraft(player);
                    } catch (Throwable error) {
                        failAfterSnapshot(player, snapshot,
                                "SDS wipe committed but Minecraft-state wipe failed: " + rootMessage(error));
                        return;
                    }

                    dispatchHardBan(player, detections, snapshot);
                })
        );
    }

    private void dispatchHardBan(Player player, List<HackDefinition> detections, File snapshot) {
        String mods = detections.stream().map(HackDefinition::displayName).collect(Collectors.joining(", "));
        String command = plugin.getConfig().getString(
                "enforcement.hard-cheat-tempban-command",
                "tempban {player} 14d Client modifications are not allowed ({mods})"
        )
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{mods}", mods);

        if (command.startsWith("/")) command = command.substring(1);
        boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (!accepted) {
            alert("<red><bold>CRITICAL:</bold></red> wipe completed for <yellow>" + player.getName()
                    + "</yellow> but the tempban command was rejected. Recovery snapshot: <white>"
                    + escape(snapshot.getAbsolutePath()) + "</white>");
            lock.unlock(player);
            active.remove(player.getUniqueId());
            return;
        }

        plugin.getLogger().warning("ClientGuard hard sanction completed for " + player.getName()
                + "; snapshot=" + snapshot.getAbsolutePath());
        active.remove(player.getUniqueId());
        // Keep the player frozen until the tempban command disconnects them.
    }

    private void failBeforeWipe(Player player, String reason) {
        alert("<red>ClientGuard sanction aborted before destructive wipe for <yellow>"
                + player.getName() + "</yellow>: " + escape(reason) + "</red>");
        lock.unlock(player);
        active.remove(player.getUniqueId());
    }

    private void failAfterSnapshot(Player player, File snapshot, String reason) {
        alert("<red><bold>ClientGuard sanction needs staff attention:</bold></red> <yellow>"
                + player.getName() + "</yellow><gray>: " + escape(reason)
                + ". Snapshot: " + escape(snapshot.getAbsolutePath()) + "</gray>");
        lock.unlock(player);
        active.remove(player.getUniqueId());
    }

    private void alert(String message) {
        plugin.getLogger().warning(message.replaceAll("<[^>]+>", ""));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("superduck.clientguard.alerts")) {
                online.sendRichMessage("<dark_aqua>[ClientGuard]</dark_aqua> " + message);
            }
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String escape(String value) {
        return value.replace("<", "\\<").replace(">", "\\>");
    }
}
