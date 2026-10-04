package com.qducks.clientguard.sanction;

import com.qducks.clientguard.SuperDuckClientGuard;
import com.qducks.clientguard.detect.HackDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Duration;
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
        if (plugin.getConfig().getInt("enforcement.hard-cheat-ban-days", 14) <= 0) {
            blockers.add("enforcement.hard-cheat-ban-days must be greater than 0");
        }
        return blockers;
    }

    /**
     * Adds a temporary ban to the server ban list without kicking yet, and verifies it is in place.
     * The server ban list is what /tempban and /unban (ModerationPlusPlus) use too, so staff can lift it.
     */
    public boolean banPlayer(Player player, int days, String reason) {
        try {
            player.ban(reason, Duration.ofDays(Math.max(1, days)), "ClientGuard", false);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("ClientGuard could not ban " + player.getName() + ": " + exception.getMessage());
            return false;
        }
        return player.isBanned();
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
        try {
            duckyBackup = duckyPvp.exportBackup(uuid);
        } catch (Throwable error) {
            failBeforeWipe(player, "could not snapshot DuckyPVP state: " + rootMessage(error));
            return;
        }

        homes.capture(player).whenComplete((homeSnapshot, homeError) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (homeError != null) {
                        failBeforeWipe(player, "could not snapshot homes: " + rootMessage(homeError));
                        return;
                    }
                    snapshots.create(player, detections, duckyBackup, homeSnapshot)
                            .whenComplete((snapshot, snapshotError) ->
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (snapshotError != null) {
                                            failBeforeWipe(player, "snapshot failed: " + rootMessage(snapshotError));
                                            return;
                                        }
                                        continueAfterSnapshot(player, detections, snapshot, homeSnapshot);
                                    })
                            );
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

        // Ban first (without kicking), so a failure later can never leave a wiped but unbanned player.
        String mods = detections.stream().map(HackDefinition::displayName).collect(Collectors.joining(", "));
        int days = plugin.getConfig().getInt("enforcement.hard-cheat-ban-days", 14);
        String reason = plugin.getConfig().getString("enforcement.hard-cheat-ban-reason",
                "Client modifications are not allowed ({mods})").replace("{mods}", mods);
        if (!banPlayer(player, days, reason)) {
            failBeforeWipe(player, "the ban could not be applied, so nothing was wiped; snapshot kept at "
                    + snapshot.getAbsolutePath());
            return;
        }

        if (!duckyPvp.discardBackup(uuid)) {
            failAfterSnapshot(player, snapshot, "DuckyPVP backup could not be safely discarded; nothing was wiped");
            return;
        }

        homes.wipe(player, homeSnapshot).whenComplete((homesWiped, homesError) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (homesError != null) {
                        failAfterSnapshot(player, snapshot,
                                "homes wipe failed; no SDS wipe was attempted: " + rootMessage(homesError));
                        return;
                    }
                    if (!Boolean.TRUE.equals(homesWiped)) {
                        failAfterSnapshot(player, snapshot,
                                "homes could not be fully verified as deleted; no SDS wipe was attempted");
                        return;
                    }
                    continueAfterExternalWipe(player, detections, snapshot);
                })
        );
    }

    private void continueAfterExternalWipe(Player player, List<HackDefinition> detections, File snapshot) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) {
            failAfterSnapshot(player, snapshot, "player disconnected before SDS wipe");
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

                    completeSanction(player, snapshot);
                })
        );
    }

    private void completeSanction(Player player, File snapshot) {
        plugin.getLogger().warning("ClientGuard hard sanction completed for " + player.getName()
                + "; snapshot=" + snapshot.getAbsolutePath());
        active.remove(player.getUniqueId());
        lock.unlock(player);
        if (player.isOnline()) {
            player.kick(Component.text(plugin.getConfig().getString("enforcement.hard-cheat-kick-message",
                    "You have been banned: client modifications are not allowed on QDucks SMP.")));
        }
    }

    private void failBeforeWipe(Player player, String reason) {
        alert("<red>ClientGuard sanction aborted before destructive wipe for <yellow>"
                + player.getName() + "</yellow>: " + escape(reason) + "</red>");
        lock.unlock(player);
        active.remove(player.getUniqueId());
    }

    /** A step after the ban failed. The player stays banned and is disconnected; staff restore from the snapshot. */
    private void failAfterSnapshot(Player player, File snapshot, String reason) {
        alert("<red><bold>ClientGuard sanction needs staff attention:</bold></red> <yellow>"
                + player.getName() + "</yellow><gray>: " + escape(reason)
                + ". The player stays banned. Snapshot: " + escape(snapshot.getAbsolutePath()) + "</gray>");
        lock.unlock(player);
        active.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.kick(Component.text("You have been banned from QDucks SMP. Contact staff if you believe this is a mistake."));
        }
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
