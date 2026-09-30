package com.qducks.superduckclientguard;

/*
 * Core response-evaluation/sign-probe approach adapted from CheckHacks by Branduzzo.
 * Original project: https://github.com/branduzzo/CheckHacks
 * Licensed under the MIT License. See THIRD_PARTY_NOTICES.md.
 */

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ClientScanService {
    private static final int LINES_PER_SIGN = 3;
    private static final String CONTROL_KEYBIND = "key.forward";

    private final SuperDuckClientGuard plugin;
    private final EnforcementService enforcement;
    private final Map<UUID, ScanSession> sessions = new ConcurrentHashMap<>();
    private volatile List<HackDefinition> definitions = List.of();

    ClientScanService(SuperDuckClientGuard plugin, EnforcementService enforcement) {
        this.plugin = plugin;
        this.enforcement = enforcement;
        reloadDefinitions();
    }

    void reloadDefinitions() {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("hacks");
        if (root == null) {
            definitions = List.of();
            plugin.getLogger().warning("ClientGuard has no hacks section; scans are disabled.");
            return;
        }

        List<HackDefinition> loaded = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;

            String display = section.getString("display-name", id);
            String key = section.getString("key", "").trim();
            String modeRaw = section.getString("mode", "").trim().toUpperCase(Locale.ROOT);
            String policyRaw = section.getString("policy", "ALERT_ONLY").trim().toUpperCase(Locale.ROOT);
            if (key.isBlank() || modeRaw.isBlank()) {
                plugin.getLogger().warning("Skipping invalid ClientGuard definition: " + id);
                continue;
            }

            try {
                loaded.add(new HackDefinition(
                        id,
                        display,
                        key,
                        DetectionMode.valueOf(modeRaw),
                        DetectionPolicy.valueOf(policyRaw)
                ));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping ClientGuard definition with invalid mode/policy: "
                        + id + " (" + exception.getMessage() + ")");
            }
        }

        definitions = List.copyOf(loaded);
        plugin.getLogger().info("Loaded " + definitions.size() + " ClientGuard detection definitions.");
    }

    boolean isChecking(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    boolean startScan(Player player, String reason) {
        if (!plugin.getConfig().getBoolean("enabled", true)) return false;
        if (definitions.isEmpty()) return false;
        if (sessions.containsKey(player.getUniqueId())) return false;
        if (isBedrockPlayer(player)) {
            enforcement.alertStaff("&7[ClientGuard] Skipped Bedrock player " + player.getName() + ".");
            return false;
        }

        return startDefinitions(player, definitions, reason, false);
    }

    private boolean startDefinitions(Player player, List<HackDefinition> checks, String reason, boolean confirmation) {
        if (checks.isEmpty() || sessions.containsKey(player.getUniqueId())) return false;

        List<List<HackDefinition>> batches = new ArrayList<>();
        for (int index = 0; index < checks.size(); index += LINES_PER_SIGN) {
            batches.add(new ArrayList<>(checks.subList(index, Math.min(index + LINES_PER_SIGN, checks.size()))));
        }

        ScanSession session = new ScanSession(player.getUniqueId(), batches, reason, confirmation);
        sessions.put(player.getUniqueId(), session);
        openCurrentBatch(player, session);
        return true;
    }

    private void openCurrentBatch(Player player, ScanSession session) {
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            cancel(player.getUniqueId());
            return;
        }

        Location signLocation = SignProbe.findAirBlock(player);
        if (signLocation == null) {
            plugin.getLogger().warning("No safe temporary sign location found for " + player.getName());
            finish(player.getUniqueId());
            return;
        }

        Block block = signLocation.getBlock();
        BlockState original = block.getState();
        Location belowLocation = signLocation.clone().subtract(0, 1, 0);
        Block below = belowLocation.getBlock();
        boolean barrierPlaced = below.getType().isAir();

        if (barrierPlaced) below.setType(Material.BARRIER, false);
        block.setType(Material.OAK_SIGN, false);

        BlockState state = block.getState();
        if (!(state instanceof Sign sign)) {
            original.update(true, false);
            if (barrierPlaced && below.getType() == Material.BARRIER) {
                below.setType(Material.AIR, false);
            }
            finish(player.getUniqueId());
            return;
        }

        List<HackDefinition> batch = session.currentBatch();
        var front = sign.getSide(Side.FRONT);
        for (int line = 0; line < LINES_PER_SIGN; line++) {
            front.line(line, line < batch.size() ? componentFor(batch.get(line)) : Component.empty());
        }
        front.line(3, Component.keybind(CONTROL_KEYBIND));
        sign.update(true, false);

        session.signLocation(signLocation);
        session.originalState(original);
        session.barrierPlaced(barrierPlaced);
        session.barrierLocation(belowLocation);

        SignProbe.setAllowedEditor(signLocation, player.getUniqueId(), plugin);
        SignProbe.sendBlockEntityPacket(player, signLocation, plugin);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != session || !player.isOnline()) return;
            SignProbe.sendOpenSignPacket(player, signLocation, plugin);
            player.sendBlockChange(signLocation, Material.AIR.createBlockData());
        }, 1L);

        long timeout = Math.max(20L, plugin.getConfig().getLong("scan.timeout-ticks", 200L));
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != session) return;
            List<HackDefinition> timedOutBatch = session.currentBatch();
            restoreCurrentSign(session);
            for (HackDefinition definition : timedOutBatch) {
                session.results().put(definition.id(), ScanResult.PROTECTED);
            }
            session.nextBatch();
            nextOrFinish(player.getUniqueId());
        }, timeout);
        session.timeoutTask(timeoutTask);
    }

    void handleResponse(Player player, String[] lines) {
        ScanSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        if (session.timeoutTask() != null) {
            session.timeoutTask().cancel();
            session.timeoutTask(null);
        }

        List<HackDefinition> batch = session.currentBatch();
        restoreCurrentSign(session);

        String control = lines.length > 3 ? lines[3].strip() : "";
        boolean exploitPreventer = control.equalsIgnoreCase(CONTROL_KEYBIND);

        StringBuilder log = new StringBuilder("ClientGuard batch response from ")
                .append(player.getName()).append(": ");

        for (int index = 0; index < batch.size(); index++) {
            HackDefinition definition = batch.get(index);
            String response = index < lines.length ? lines[index].strip() : "";
            ScanResult result = evaluate(definition, response, exploitPreventer);
            session.results().put(definition.id(), result);
            if (index > 0) log.append(", ");
            log.append(definition.id()).append('=').append(result);
        }
        plugin.getLogger().info(log.toString());

        session.nextBatch();
        nextOrFinish(player.getUniqueId());
    }

    private void nextOrFinish(UUID playerId) {
        ScanSession session = sessions.get(playerId);
        if (session == null) return;

        if (!session.hasMoreBatches()) {
            finish(playerId);
            return;
        }

        long delay = Math.max(1L, plugin.getConfig().getLong("scan.between-sign-ticks", 5L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ScanSession current = sessions.get(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (current != session || player == null || !player.isOnline()) {
                cancel(playerId);
                return;
            }
            openCurrentBatch(player, session);
        }, delay);
    }

    private ScanResult evaluate(HackDefinition hack, String response, boolean exploitPreventer) {
        if (response.isEmpty()) return ScanResult.NOT_DETECTED;

        String lowerKey = hack.lowerKey();
        int keyLength = lowerKey.length();
        if (response.length() == keyLength + 1
                && response.regionMatches(true, 0, lowerKey, 0, keyLength)
                && Character.isLetter(response.charAt(keyLength))) {
            return ScanResult.NOT_DETECTED;
        }

        return switch (hack.mode()) {
            case METEOR -> {
                if (response.equalsIgnoreCase(hack.key())) yield ScanResult.DETECTED;
                if (startsWithIgnoreCase(response, hack.lowerFallback())) yield ScanResult.NOT_DETECTED;
                yield ScanResult.DETECTED;
            }
            case TRANSLATE -> {
                if (startsWithIgnoreCase(response, hack.lowerFallback())) yield ScanResult.NOT_DETECTED;
                if (response.equalsIgnoreCase(hack.key())) yield ScanResult.PROTECTED;
                yield ScanResult.DETECTED;
            }
            case KEYBIND -> {
                if (exploitPreventer && response.equalsIgnoreCase(hack.key())) yield ScanResult.PROTECTED;
                if (response.equalsIgnoreCase(hack.key())) yield ScanResult.NOT_DETECTED;
                yield ScanResult.DETECTED;
            }
        };
    }

    private void finish(UUID playerId) {
        ScanSession session = sessions.remove(playerId);
        if (session == null) return;

        if (session.timeoutTask() != null) session.timeoutTask().cancel();
        restoreCurrentSign(session);

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        Map<String, HackDefinition> byId = new LinkedHashMap<>();
        for (List<HackDefinition> batch : session.batches()) {
            for (HackDefinition definition : batch) byId.put(definition.id(), definition);
        }

        List<HackDefinition> detected = new ArrayList<>();
        List<HackDefinition> protectedResults = new ArrayList<>();
        for (Map.Entry<String, HackDefinition> entry : byId.entrySet()) {
            ScanResult result = session.results().getOrDefault(entry.getKey(), ScanResult.SKIPPED);
            if (result == ScanResult.DETECTED) detected.add(entry.getValue());
            if (result == ScanResult.PROTECTED) protectedResults.add(entry.getValue());
        }

        if (!protectedResults.isEmpty()) {
            enforcement.alertStaff("&e[ClientGuard] " + player.getName()
                    + " returned PROTECTED/blocked results for: " + displayNames(protectedResults)
                    + ". No punishment.");
        }

        if (!session.confirmation()
                && plugin.getConfig().getBoolean("scan.double-check", true)
                && !detected.isEmpty()) {
            enforcement.alertStaff("&e[ClientGuard] DoubleCheck for " + player.getName()
                    + ": " + displayNames(detected));
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> {
                        if (player.isOnline()) {
                            startDefinitions(player, detected, session.reason(), true);
                        }
                    },
                    2L);
            return;
        }

        if (session.confirmation()) {
            if (detected.isEmpty()) {
                enforcement.alertStaff("&a[ClientGuard] DoubleCheck cleared " + player.getName()
                        + "; no action.");
                return;
            }
            enforcement.handleConfirmed(player, detected);
            return;
        }

        if (!detected.isEmpty()) enforcement.handleConfirmed(player, detected);
    }

    void cancel(UUID playerId) {
        ScanSession session = sessions.remove(playerId);
        if (session == null) return;
        if (session.timeoutTask() != null) session.timeoutTask().cancel();
        restoreCurrentSign(session);
    }

    void shutdown() {
        for (UUID playerId : List.copyOf(sessions.keySet())) cancel(playerId);
    }

    private void restoreCurrentSign(ScanSession session) {
        if (session.signLocation() == null) return;

        try {
            if (session.originalState() != null) session.originalState().update(true, false);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to restore ClientGuard temporary sign: "
                    + exception.getMessage());
        }

        if (session.barrierPlaced() && session.barrierLocation() != null) {
            try {
                Block block = session.barrierLocation().getBlock();
                if (block.getType() == Material.BARRIER) block.setType(Material.AIR, false);
            } catch (Exception exception) {
                plugin.getLogger().warning("Failed to restore ClientGuard temporary barrier: "
                        + exception.getMessage());
            }
        }

        session.signLocation(null);
        session.originalState(null);
        session.barrierLocation(null);
        session.barrierPlaced(false);
    }

    private Component componentFor(HackDefinition hack) {
        return switch (hack.mode()) {
            case METEOR, TRANSLATE -> Component.translatable(hack.key(), hack.fallback());
            case KEYBIND -> Component.keybind(hack.key());
        };
    }

    private boolean isBedrockPlayer(Player player) {
        if (!plugin.getConfig().getBoolean("scan.bedrock.skip", true)) return false;

        try {
            if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object api = apiClass.getMethod("getInstance").invoke(null);
                Method method = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                Object result = method.invoke(api, player.getUniqueId());
                if (result instanceof Boolean bool) return bool;
            }
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Floodgate detection failed; using configured prefix fallback: "
                    + exception.getMessage());
        }

        for (String prefix : plugin.getConfig().getStringList("scan.bedrock.fallback-prefixes")) {
            if (!prefix.isEmpty() && player.getName().startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return prefix.length() <= value.length()
                && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String displayNames(List<HackDefinition> definitions) {
        return definitions.stream().map(HackDefinition::displayName)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }
}
