package com.qducks.superducksystem.maintenance;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MaintenanceService {
    private final SuperDuckSystem plugin;

    private final NamespacedKey previousGameModeKey;
    private final NamespacedKey previousWorldKey;
    private final NamespacedKey previousXKey;
    private final NamespacedKey previousYKey;
    private final NamespacedKey previousZKey;
    private final NamespacedKey previousYawKey;
    private final NamespacedKey previousPitchKey;

    private final Set<String> whitelistedPlayers = new HashSet<>();
    private final Set<String> allowedCommands = new HashSet<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Map<UUID, PermissionAttachment> maintenanceAttachments = new HashMap<>();

    private boolean active;
    private String whitelistPermission;
    private String bypassPermission;
    private boolean forceAdventure;
    private String blockedCommandMessage;
    private String maintenanceMessage;
    private String title;
    private String subtitle;

    public MaintenanceService(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.previousGameModeKey = new NamespacedKey(plugin, "maintenance_previous_gamemode");
        this.previousWorldKey = new NamespacedKey(plugin, "maintenance_previous_world");
        this.previousXKey = new NamespacedKey(plugin, "maintenance_previous_x");
        this.previousYKey = new NamespacedKey(plugin, "maintenance_previous_y");
        this.previousZKey = new NamespacedKey(plugin, "maintenance_previous_z");
        this.previousYawKey = new NamespacedKey(plugin, "maintenance_previous_yaw");
        this.previousPitchKey = new NamespacedKey(plugin, "maintenance_previous_pitch");
    }

    public void start() {
        reload();
        reconcileOnlinePlayers();
    }

    public void reload() {
        FileConfiguration config = plugin.configs().maintenance();
        active = config.getBoolean("active", false);
        whitelistPermission = config.getString("whitelist.permission", "superduck.maintenance.whitelist");
        bypassPermission = config.getString("whitelist.alternate-bypass-permission", "superduck.maintenance.bypass");
        forceAdventure = config.getBoolean("restrictions.force-adventure", true);
        blockedCommandMessage = config.getString("messages.command-blocked",
                "<red>The server is currently in maintenance mode.</red>");
        maintenanceMessage = config.getString("messages.join",
                "<yellow>The server is currently in maintenance mode.</yellow>");
        title = config.getString("messages.title", "<gold><bold>MAINTENANCE</bold></gold>");
        subtitle = config.getString("messages.subtitle", "<gray>Please wait while we work on the server.</gray>");

        whitelistedPlayers.clear();
        for (String entry : config.getStringList("whitelist.players")) {
            if (entry != null && !entry.isBlank()) {
                whitelistedPlayers.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }

        allowedCommands.clear();
        for (String command : config.getStringList("allowed-commands")) {
            if (command != null && !command.isBlank()) {
                allowedCommands.add(normalizeCommand(command));
            }
        }
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean enabled) {
        if (active == enabled) return;
        active = enabled;
        plugin.configs().maintenance().set("active", enabled);
        plugin.configs().saveMaintenance();

        if (enabled) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!isWhitelisted(player)) apply(player, true);
            }
            Bukkit.broadcast(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<gold><bold>QDucks maintenance mode enabled.</bold></gold>"));
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                restorePlayer(player);
            }
            Bukkit.broadcast(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<green><bold>QDucks maintenance mode disabled.</bold></green>"));
        }
    }

    public boolean isRestricted(Player player) {
        return active && player != null && !isWhitelisted(player);
    }

    public boolean isWhitelisted(Player player) {
        if (player == null) return true;
        if (player.hasPermission("superduck.admin.maintenancemode")) return true;
        if (whitelistPermission != null && !whitelistPermission.isBlank() && player.hasPermission(whitelistPermission)) {
            return true;
        }
        if (bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission)) {
            return true;
        }

        String name = player.getName().toLowerCase(Locale.ROOT);
        String uuid = player.getUniqueId().toString().toLowerCase(Locale.ROOT);
        return whitelistedPlayers.contains(name) || whitelistedPlayers.contains(uuid);
    }

    public boolean isCommandAllowed(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return false;
        return allowedCommands.contains(normalizeCommand(rawCommand));
    }

    public void handleJoin(Player player) {
        if (active) {
            if (isWhitelisted(player)) {
                restorePlayer(player);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && isRestricted(player)) apply(player, true);
                });
            }
        } else {
            restorePlayer(player);
        }
    }

    public void handleQuit(Player player) {
        if (player == null) return;
        internalTeleports.remove(player.getUniqueId());
        PermissionAttachment attachment = maintenanceAttachments.remove(player.getUniqueId());
        if (attachment != null) {
            try {
                player.removeAttachment(attachment);
            } catch (IllegalArgumentException ignored) {
                // Bukkit can already have removed plugin attachments while the player is disconnecting.
            }
        }
    }

    public void apply(Player player, boolean teleport) {
        if (!isRestricted(player)) return;

        // Save only once. If the player reconnects or the server restarts during maintenance,
        // this keeps the real pre-maintenance position instead of overwriting it with the room.
        savePreviousState(player);
        addMaintenanceBypasses(player);

        if (forceAdventure && player.getGameMode() != GameMode.ADVENTURE) {
            player.setGameMode(GameMode.ADVENTURE);
        }

        player.setFireTicks(0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);

        if (teleport) {
            Location room = roomLocation();
            if (room != null) {
                internalTeleports.add(player.getUniqueId());
                try {
                    if (!player.teleport(room)) {
                        plugin.getLogger().warning("Maintenance teleport was cancelled for " + player.getName());
                    }
                } finally {
                    internalTeleports.remove(player.getUniqueId());
                }
            }
        }

        if (title != null && !title.isBlank()) {
            String sub = subtitle == null ? "" : subtitle;
            player.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(title),
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(sub)
            ));
        }
        if (maintenanceMessage != null && !maintenanceMessage.isBlank()) {
            player.sendRichMessage(maintenanceMessage);
        }
    }

    public void restorePlayer(Player player) {
        if (player == null) return;

        // Keep temporary combat bypasses during the restore teleport so DuckyPVP or
        // global combat cannot block the player from returning to their saved location.
        addMaintenanceBypasses(player);

        PersistentDataContainer data = player.getPersistentDataContainer();
        Location previousLocation = readPreviousLocation(player);
        if (previousLocation != null) {
            internalTeleports.add(player.getUniqueId());
            boolean restored;
            try {
                restored = player.teleport(previousLocation);
            } finally {
                internalTeleports.remove(player.getUniqueId());
            }

            if (restored) {
                clearPreviousLocation(data);
            } else {
                plugin.getLogger().warning("Could not restore pre-maintenance location for " + player.getName()
                        + "; saved location will be kept for the next join.");
            }
        }

        String storedGameMode = data.get(previousGameModeKey, PersistentDataType.STRING);
        if (storedGameMode != null) {
            data.remove(previousGameModeKey);
            try {
                GameMode previous = GameMode.valueOf(storedGameMode);
                if (player.getGameMode() != previous) player.setGameMode(previous);
            } catch (IllegalArgumentException ignored) {
                if (player.getGameMode() == GameMode.ADVENTURE) player.setGameMode(GameMode.SURVIVAL);
            }
        }

        removeMaintenanceBypasses(player);
    }

    public boolean isInternalTeleport(Player player) {
        return player != null && internalTeleports.contains(player.getUniqueId());
    }

    public void sendBlockedCommand(Player player) {
        if (blockedCommandMessage != null && !blockedCommandMessage.isBlank()) {
            player.sendRichMessage(blockedCommandMessage);
        }
    }

    public boolean setRoom(Location location) {
        if (location == null || location.getWorld() == null) return false;
        FileConfiguration config = plugin.configs().maintenance();
        config.set("room.world", location.getWorld().getName());
        config.set("room.x", location.getX());
        config.set("room.y", location.getY());
        config.set("room.z", location.getZ());
        config.set("room.yaw", location.getYaw());
        config.set("room.pitch", location.getPitch());
        plugin.configs().saveMaintenance();
        return true;
    }

    public Location roomLocation() {
        FileConfiguration config = plugin.configs().maintenance();
        String worldName = config.getString("room.world", "spawn");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Maintenance room world does not exist: " + worldName);
            return null;
        }
        return new Location(
                world,
                config.getDouble("room.x", 0.5),
                config.getDouble("room.y", 100.0),
                config.getDouble("room.z", 0.5),
                (float) config.getDouble("room.yaw", 0.0),
                (float) config.getDouble("room.pitch", 0.0)
        );
    }

    public int restrictedOnlineCount() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isRestricted(player)) count++;
        }
        return count;
    }

    public void reconcileOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isRestricted(player)) apply(player, active);
            else restorePlayer(player);
        }
    }

    private void savePreviousState(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();

        if (!data.has(previousGameModeKey, PersistentDataType.STRING)) {
            data.set(previousGameModeKey, PersistentDataType.STRING, player.getGameMode().name());
        }

        if (!data.has(previousWorldKey, PersistentDataType.STRING)) {
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world != null) {
                data.set(previousWorldKey, PersistentDataType.STRING, world.getName());
                data.set(previousXKey, PersistentDataType.DOUBLE, location.getX());
                data.set(previousYKey, PersistentDataType.DOUBLE, location.getY());
                data.set(previousZKey, PersistentDataType.DOUBLE, location.getZ());
                data.set(previousYawKey, PersistentDataType.FLOAT, location.getYaw());
                data.set(previousPitchKey, PersistentDataType.FLOAT, location.getPitch());
            }
        }
    }

    private Location readPreviousLocation(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String worldName = data.get(previousWorldKey, PersistentDataType.STRING);
        Double x = data.get(previousXKey, PersistentDataType.DOUBLE);
        Double y = data.get(previousYKey, PersistentDataType.DOUBLE);
        Double z = data.get(previousZKey, PersistentDataType.DOUBLE);
        Float yaw = data.get(previousYawKey, PersistentDataType.FLOAT);
        Float pitch = data.get(previousPitchKey, PersistentDataType.FLOAT);

        if (worldName == null || x == null || y == null || z == null) return null;

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Cannot restore maintenance location for " + player.getName()
                    + ": world '" + worldName + "' is not loaded.");
            return null;
        }

        return new Location(world, x, y, z, yaw == null ? 0.0f : yaw, pitch == null ? 0.0f : pitch);
    }

    private void clearPreviousLocation(PersistentDataContainer data) {
        data.remove(previousWorldKey);
        data.remove(previousXKey);
        data.remove(previousYKey);
        data.remove(previousZKey);
        data.remove(previousYawKey);
        data.remove(previousPitchKey);
    }

    private void addMaintenanceBypasses(Player player) {
        if (maintenanceAttachments.containsKey(player.getUniqueId())) return;
        PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission("superduck.combat.bypass", true);
        attachment.setPermission("duckypvp.combat.bypass", true);
        maintenanceAttachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
    }

    private void removeMaintenanceBypasses(Player player) {
        PermissionAttachment attachment = maintenanceAttachments.remove(player.getUniqueId());
        if (attachment == null) return;
        try {
            player.removeAttachment(attachment);
        } catch (IllegalArgumentException ignored) {
            // The attachment can already be gone during plugin shutdown/reload.
        }
        player.recalculatePermissions();
    }

    private static String normalizeCommand(String raw) {
        String command = raw.trim().toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank()) return "";
        command = command.split("\\s+", 2)[0];
        int namespace = command.lastIndexOf(':');
        return namespace >= 0 ? command.substring(namespace + 1) : command;
    }
}
