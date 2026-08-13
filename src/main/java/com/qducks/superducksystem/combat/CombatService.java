package com.qducks.superducksystem.combat;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CombatService {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final SuperDuckSystem plugin;
    private final Map<UUID, Long> taggedUntil = new HashMap<>();
    private final Set<String> blockedCommands = new HashSet<>();
    private BukkitTask task;
    private long tagMillis;
    private String bypassPermission;
    private String actionbar;
    private String blockedMessage;
    private String releasedMessage;

    public CombatService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reloadSettings();
        stopTask();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    public void reload() {
        reloadSettings();
    }

    public void stop() {
        stopTask();
        taggedUntil.clear();
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void reloadSettings() {
        tagMillis = Math.max(1L, plugin.getConfig().getLong("combat.tag-seconds", 20L)) * 1000L;
        bypassPermission = plugin.getConfig().getString("combat.bypass-permission", "superduck.combat.bypass");
        actionbar = plugin.getConfig().getString("combat.actionbar", "&c&lCOMBAT &8» &f%time%s &7• &cDo not disconnect or escape");
        blockedMessage = plugin.getConfig().getString("combat.blocked-command-message", "&c&lCOMBAT &8» &7You cannot use &f/%command% &7for another &c%time%s&7.");
        releasedMessage = plugin.getConfig().getString("combat.released-message", "&a&lCOMBAT &8» &7You are no longer in combat.");

        blockedCommands.clear();
        for (String command : plugin.getConfig().getStringList("combat.blocked-commands")) {
            if (command != null && !command.isBlank()) blockedCommands.add(normalize(command));
        }
        if (blockedCommands.isEmpty()) {
            blockedCommands.addAll(Set.of("spawn", "rtp", "wild", "home", "back", "warp", "tpa", "tpahere", "tpaccept"));
        }
    }

    public void tag(Player first, Player second) {
        if (first == null || second == null || first.getUniqueId().equals(second.getUniqueId())) return;
        tag(first);
        tag(second);
    }

    public void tag(Player player) {
        if (player == null || hasBypass(player)) return;
        taggedUntil.put(player.getUniqueId(), System.currentTimeMillis() + tagMillis);
        show(player);
    }

    public void clear(Player player, boolean notify) {
        if (player == null) return;
        boolean existed = taggedUntil.remove(player.getUniqueId()) != null;
        if (notify && existed && releasedMessage != null && !releasedMessage.isBlank()) {
            player.sendActionBar(LEGACY.deserialize(releasedMessage));
        }
    }

    public boolean isTagged(Player player) {
        if (player == null || hasBypass(player)) return false;
        Long until = taggedUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public long secondsRemaining(Player player) {
        Long until = taggedUntil.get(player.getUniqueId());
        if (until == null) return 0L;
        long remaining = Math.max(0L, until - System.currentTimeMillis());
        return remaining == 0L ? 0L : (remaining + 999L) / 1000L;
    }

    public boolean shouldBlockCommand(Player player, String raw) {
        if (!isTagged(player) || raw == null || raw.isBlank()) return false;
        String label = raw.trim();
        if (label.startsWith("/")) label = label.substring(1);
        if (label.isBlank()) return false;
        label = label.split("\\s+", 2)[0];
        return blockedCommands.contains(normalize(label));
    }

    public String commandLabel(String raw) {
        String label = raw == null ? "command" : raw.trim();
        if (label.startsWith("/")) label = label.substring(1);
        if (label.isBlank()) return "command";
        return normalize(label.split("\\s+", 2)[0]);
    }

    public void sendBlocked(Player player, String command) {
        String text = replace(blockedMessage, player).replace("%command%", command);
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', text));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(taggedUntil.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                taggedUntil.remove(uuid);
                continue;
            }
            Long until = taggedUntil.get(uuid);
            if (hasBypass(player) || until == null || until <= now) {
                clear(player, true);
                continue;
            }
            show(player);
        }
    }

    private void show(Player player) {
        if (actionbar != null && !actionbar.isBlank()) {
            player.sendActionBar(LEGACY.deserialize(replace(actionbar, player)));
        }
    }

    private String replace(String input, Player player) {
        return (input == null ? "" : input).replace("%time%", String.valueOf(secondsRemaining(player)));
    }

    private boolean hasBypass(Player player) {
        return bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission);
    }

    private static String normalize(String input) {
        String result = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (result.startsWith("/")) result = result.substring(1);
        int namespace = result.lastIndexOf(':');
        return namespace >= 0 ? result.substring(namespace + 1) : result;
    }
}
