package com.qducks.clientguard.detect;

import com.qducks.clientguard.SuperDuckClientGuard;
import com.qducks.clientguard.policy.PolicyEngine;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientScanner {
    private static final int LINES_PER_SIGN = 3;
    private static final String CONTROL_KEYBIND = "key.forward";

    private final SuperDuckClientGuard plugin;
    private final PolicyEngine policy;
    private final Map<UUID, ScanSession> active = new ConcurrentHashMap<>();
    private volatile List<HackDefinition> definitions = List.of();

    public ClientScanner(SuperDuckClientGuard plugin, PolicyEngine policy) {
        this.plugin = plugin;
        this.policy = policy;
        reloadDefinitions();
    }

    public void reloadDefinitions() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("hacks");
        if (section == null) {
            definitions = List.of();
            return;
        }

        List<HackDefinition> loaded = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            String base = "hacks." + id + ".";
            String display = plugin.getConfig().getString(base + "display-name", id);
            String key = plugin.getConfig().getString(base + "key", "");
            String rawMode = plugin.getConfig().getString(base + "mode", "TRANSLATE");
            if (key.isBlank()) continue;
            try {
                loaded.add(new HackDefinition(id, display, key,
                        DetectionMode.valueOf(rawMode.toUpperCase())));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping ClientGuard definition " + id
                        + ": invalid mode " + rawMode);
            }
        }
        definitions = List.copyOf(loaded);
        plugin.getLogger().info("Loaded " + definitions.size() + " ClientGuard signatures.");
    }

    public void scanAll(Player player) {
        if (!plugin.getConfig().getBoolean("enabled", true)) return;
        if (!player.isOnline() || player.hasPermission("superduck.clientguard.bypass")) return;
        if (isBedrock(player)) return;
        if (definitions.isEmpty() || active.containsKey(player.getUniqueId())) return;
        startScan(player, definitions, false);
    }

    public boolean isChecking(UUID playerId) {
        return active.containsKey(playerId);
    }

    public void handleResponse(Player player, String[] lines) {
        ScanSession session = active.get(player.getUniqueId());
        if (session == null) return;

        if (session.timeoutTask != null) session.timeoutTask.cancel();
        restoreCurrentSign(session);

        List<HackDefinition> batch = session.currentBatch();
        String control = lines.length > 3 ? lines[3].strip() : "";
        boolean exploitPreventer = control.equalsIgnoreCase(CONTROL_KEYBIND);

        for (int i = 0; i < batch.size(); i++) {
            HackDefinition hack = batch.get(i);
            String response = i < lines.length ? lines[i] : "";
            HackResult result = ResponseEvaluator.evaluate(hack, response, exploitPreventer);
            session.results.put(hack.id(), result);
        }

        session.batchIndex++;
        scheduleNextOrFinish(player, session);
    }

    public void shutdown() {
        for (ScanSession session : active.values()) {
            if (session.timeoutTask != null) session.timeoutTask.cancel();
            restoreCurrentSign(session);
        }
        active.clear();
    }

    private void startScan(Player player, List<HackDefinition> hacks, boolean confirmation) {
        List<List<HackDefinition>> batches = new ArrayList<>();
        for (int i = 0; i < hacks.size(); i += LINES_PER_SIGN) {
            batches.add(new ArrayList<>(hacks.subList(i, Math.min(i + LINES_PER_SIGN, hacks.size()))));
        }
        if (batches.isEmpty()) return;

        ScanSession session = new ScanSession(player.getUniqueId(), batches, confirmation);
        if (active.putIfAbsent(player.getUniqueId(), session) != null) return;
        processBatch(player, session);
    }

    private void processBatch(Player player, ScanSession session) {
        if (!player.isOnline() || !session.hasMoreBatches()) {
            finish(session);
            return;
        }

        Location signLocation = SignUtil.findAirBlock(player);
        if (signLocation == null) {
            active.remove(player.getUniqueId());
            alert("<yellow>ClientGuard could not find a safe temporary sign location for "
                    + player.getName() + ".</yellow>");
            return;
        }

        Block block = signLocation.getBlock();
        BlockState originalState = block.getState();

        Location belowLocation = signLocation.clone().subtract(0, 1, 0);
        Block belowBlock = belowLocation.getBlock();
        boolean barrierPlaced = belowBlock.getType().isAir();
        if (barrierPlaced) belowBlock.setType(Material.BARRIER, false);

        block.setType(Material.OAK_SIGN, false);
        BlockState fresh = block.getState();
        if (!(fresh instanceof Sign sign)) {
            originalState.update(true, false);
            if (barrierPlaced) belowBlock.setType(Material.AIR, false);
            active.remove(player.getUniqueId());
            return;
        }

        List<HackDefinition> batch = session.currentBatch();
        var front = sign.getSide(Side.FRONT);
        for (int i = 0; i < LINES_PER_SIGN; i++) {
            front.line(i, i < batch.size() ? componentFor(batch.get(i)) : Component.empty());
        }
        front.line(3, Component.keybind(CONTROL_KEYBIND));
        sign.update(true, false);

        session.signLocation = signLocation;
        session.originalState = originalState;
        session.barrierPlaced = barrierPlaced;
        session.barrierLocation = belowLocation;

        SignUtil.setAllowedEditor(signLocation, player.getUniqueId(), plugin);
        SignUtil.sendBlockEntityPacket(player, signLocation, plugin);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active.containsKey(player.getUniqueId()) || !player.isOnline()) return;
            SignUtil.sendOpenSignPacket(player, signLocation, plugin);
            player.sendBlockChange(signLocation, Material.AIR.createBlockData());
        }, 1L);

        long timeoutTicks = Math.max(20L, plugin.getConfig().getLong("scan.timeout-ticks", 200L));
        session.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ScanSession current = active.get(player.getUniqueId());
            if (current != session) return;
            restoreCurrentSign(session);
            for (HackDefinition hack : batch) {
                session.results.put(hack.id(), HackResult.PROTECTED);
            }
            session.batchIndex++;
            scheduleNextOrFinish(player, session);
        }, timeoutTicks);
    }

    private Component componentFor(HackDefinition hack) {
        return switch (hack.mode()) {
            case METEOR, TRANSLATE -> Component.translatable(hack.key(), hack.fallback());
            case KEYBIND -> Component.keybind(hack.key());
        };
    }

    private void scheduleNextOrFinish(Player player, ScanSession session) {
        if (!session.hasMoreBatches()) {
            finish(session);
            return;
        }

        long delay = Math.max(1L, plugin.getConfig().getLong("scan.between-batches-ticks", 5L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ScanSession current = active.get(player.getUniqueId());
            if (current == session && player.isOnline()) processBatch(player, session);
            else if (current == session) active.remove(player.getUniqueId());
        }, delay);
    }

    private void finish(ScanSession session) {
        active.remove(session.playerId, session);
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null || !player.isOnline()) return;

        List<HackDefinition> all = session.batches.stream().flatMap(List::stream).toList();
        List<HackDefinition> detected = all.stream()
                .filter(h -> session.results.getOrDefault(h.id(), HackResult.SKIPPED) == HackResult.DETECTED)
                .toList();
        List<HackDefinition> protectedResults = all.stream()
                .filter(h -> session.results.getOrDefault(h.id(), HackResult.SKIPPED) == HackResult.PROTECTED)
                .toList();

        if (!session.confirmation
                && plugin.getConfig().getBoolean("scan.double-check", true)
                && (!detected.isEmpty() || !protectedResults.isEmpty())) {
            List<HackDefinition> recheck = all.stream()
                    .filter(h -> {
                        HackResult result = session.results.getOrDefault(h.id(), HackResult.SKIPPED);
                        return result == HackResult.DETECTED || result == HackResult.PROTECTED;
                    })
                    .toList();
            alert("<gray>DoubleCheck:</gray> <yellow>" + player.getName()
                    + "</yellow> <gray>flagged; running confirmation scan.</gray>");
            startScan(player, recheck, true);
            return;
        }

        if (!protectedResults.isEmpty()) {
            String names = protectedResults.stream().map(HackDefinition::displayName)
                    .reduce((a, b) -> a + ", " + b).orElse("unknown");
            alert("<yellow>" + player.getName() + "</yellow> <gray>returned PROTECTED for:</gray> "
                    + "<yellow>" + names + "</yellow> <gray>(no automatic punishment)</gray>");
        }

        if (!detected.isEmpty()) policy.handleConfirmed(player, detected);
    }

    private void restoreCurrentSign(ScanSession session) {
        Location location = session.signLocation;
        if (location == null) return;
        try {
            if (session.originalState != null) session.originalState.update(true, false);
        } catch (Exception exception) {
            plugin.getLogger().warning("ClientGuard sign restore failed: " + exception.getMessage());
        }
        if (session.barrierPlaced && session.barrierLocation != null) {
            try {
                session.barrierLocation.getBlock().setType(Material.AIR, false);
            } catch (Exception exception) {
                plugin.getLogger().warning("ClientGuard barrier restore failed: " + exception.getMessage());
            }
        }
        session.signLocation = null;
    }

    private boolean isBedrock(Player player) {
        for (String prefix : plugin.getConfig().getStringList("scan.bedrock-name-prefixes")) {
            if (!prefix.isBlank() && player.getName().startsWith(prefix)) return true;
        }
        return isGeyserPlayer(player.getUniqueId()) || isFloodgatePlayer(player.getUniqueId());
    }

    private boolean isGeyserPlayer(UUID uuid) {
        try {
            Class<?> type = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = type.getMethod("api").invoke(null);
            return (Boolean) type.getMethod("isBedrockPlayer", UUID.class).invoke(api, uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isFloodgatePlayer(UUID uuid) {
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = type.getMethod("getInstance").invoke(null);
            return (Boolean) type.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void alert(String message) {
        plugin.getLogger().info(message.replaceAll("<[^>]+>", ""));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("superduck.clientguard.alerts")) {
                online.sendRichMessage("<dark_aqua>[ClientGuard]</dark_aqua> " + message);
            }
        }
    }
}
