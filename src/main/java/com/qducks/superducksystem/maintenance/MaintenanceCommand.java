package com.qducks.superducksystem.maintenance;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MaintenanceCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckSystem plugin;
    private final MaintenanceService maintenance;

    public MaintenanceCommand(SuperDuckSystem plugin, MaintenanceService maintenance) {
        this.plugin = plugin;
        this.maintenance = maintenance;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("superduck.admin.maintenancemode")) {
            sender.sendRichMessage("<red>You do not have permission to manage maintenance mode.</red>");
            return true;
        }

        if (args.length == 0) {
            maintenance.setActive(!maintenance.isActive());
            sendStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                maintenance.setActive(true);
                sendStatus(sender);
            }
            case "off" -> {
                maintenance.setActive(false);
                sendStatus(sender);
            }
            case "status" -> sendStatus(sender);
            case "setroom" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>Only a player can set the maintenance room location.</red>");
                    return true;
                }
                maintenance.setRoom(player.getLocation());
                sender.sendRichMessage("<green>Maintenance room set to your current location.</green>");
                if (maintenance.isActive()) maintenance.reconcileOnlinePlayers();
            }
            case "reload" -> {
                plugin.configs().reloadMaintenance();
                maintenance.reload();
                maintenance.reconcileOnlinePlayers();
                sender.sendRichMessage("<green>Maintenance configuration reloaded from disk.</green>");
                sendStatus(sender);
            }
            case "debug" -> debug(sender, args);
            case "whitelist" -> whitelist(sender, args);
            default -> sender.sendRichMessage("<red>Usage: /" + label
                    + " [on|off|status|setroom|reload|debug|whitelist]</red>");
        }
        return true;
    }

    private void whitelist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendRichMessage("<red>Usage: /mm whitelist <add|remove|list> [player]</red>");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            List<String> entries = plugin.configs().maintenance().getStringList("whitelist.players");
            if (entries.isEmpty()) {
                sender.sendRichMessage("<gray>Maintenance whitelist:</gray> <white>(empty)</white>");
            } else {
                sender.sendRichMessage("<gray>Maintenance whitelist:</gray> <white>"
                        + String.join(", ", entries) + "</white>");
            }
            return;
        }

        if (!action.equals("add") && !action.equals("remove")) {
            sender.sendRichMessage("<red>Usage: /mm whitelist <add|remove|list> [player]</red>");
            return;
        }

        if (args.length < 3 || args[2].isBlank()) {
            sender.sendRichMessage("<red>Usage: /mm whitelist " + action + " <player></red>");
            return;
        }

        String playerName = args[2];
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) playerName = online.getName();

        FileConfiguration config = plugin.configs().maintenance();
        List<String> entries = new ArrayList<>(config.getStringList("whitelist.players"));
        String finalPlayerName = playerName;

        if (action.equals("add")) {
            boolean alreadyListed = entries.stream().anyMatch(entry -> entry.equalsIgnoreCase(finalPlayerName));
            if (!alreadyListed) entries.add(finalPlayerName);
            config.set("whitelist.players", entries);
            plugin.configs().saveMaintenance();

            boolean luckPermsUpdated = updateLuckPerms(finalPlayerName, true);
            maintenance.reload();
            maintenance.reconcileOnlinePlayers();

            sender.sendRichMessage("<green>Added <white>" + finalPlayerName
                    + "</white> to the maintenance whitelist.</green>");
            if (!luckPermsUpdated) {
                sender.sendRichMessage("<yellow>YAML was updated, but LuckPerms could not be updated automatically.</yellow>");
            }
            return;
        }

        String onlineUuid = online == null ? null : online.getUniqueId().toString();
        entries.removeIf(entry -> entry.equalsIgnoreCase(finalPlayerName)
                || (onlineUuid != null && entry.equalsIgnoreCase(onlineUuid)));
        config.set("whitelist.players", entries);
        plugin.configs().saveMaintenance();

        // Explicit false is intentional: it overrides inherited * / owner permissions.
        boolean luckPermsUpdated = updateLuckPerms(finalPlayerName, false);
        maintenance.reload();
        maintenance.reconcileOnlinePlayers();

        sender.sendRichMessage("<green>Removed <white>" + finalPlayerName
                + "</white> from the maintenance whitelist.</green>");
        if (!luckPermsUpdated) {
            sender.sendRichMessage("<yellow>YAML was updated, but LuckPerms could not be updated automatically.</yellow>");
        }
    }

    private boolean updateLuckPerms(String playerName, boolean allowed) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) return false;

        String permission = maintenance.whitelistPermissionNode();
        if (permission == null || permission.isBlank()) {
            permission = "superduck.maintenance.whitelist";
        }

        return Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "lp user " + playerName + " permission set " + permission + " " + allowed
        );
    }

    private void debug(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendRichMessage("<red>That player is not online.</red>");
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendRichMessage("<red>Usage: /mm debug <player></red>");
            return;
        }

        List<String> loadedEntries = plugin.configs().maintenance().getStringList("whitelist.players");
        File configFile = new File(plugin.getDataFolder(), "maintenance.yml");

        sender.sendRichMessage("<gold><bold>Maintenance Debug</bold></gold> <gray>for</gray> <white>" + target.getName() + "</white>");
        sender.sendRichMessage("<gray>Mode active:</gray> " + yesNo(maintenance.isActive()));
        sender.sendRichMessage("<gray>OP:</gray> " + yesNo(target.isOp()));
        sender.sendRichMessage("<gray>Admin command permission:</gray> "
                + yesNo(target.hasPermission("superduck.admin.maintenancemode")));
        sender.sendRichMessage("<gray>Whitelist permission <white>" + maintenance.whitelistPermissionNode()
                + "</white>:</gray> " + yesNo(maintenance.hasWhitelistPermission(target)));
        sender.sendRichMessage("<gray>Listed in maintenance.yml:</gray> " + yesNo(maintenance.isListedInConfig(target)));
        sender.sendRichMessage("<gray>Loaded whitelist entries:</gray> <white>"
                + (loadedEntries.isEmpty() ? "(empty)" : String.join(", ", loadedEntries)) + "</white>");
        sender.sendRichMessage("<gray>Config file:</gray> <white>" + configFile.getAbsolutePath() + "</white>");
        sender.sendRichMessage("<gray>Final whitelisted:</gray> " + yesNo(maintenance.isWhitelisted(target)));
        sender.sendRichMessage("<gray>Final restricted:</gray> " + yesNo(maintenance.isRestricted(target)));
    }

    private String yesNo(boolean value) {
        return value ? "<green>YES</green>" : "<red>NO</red>";
    }

    private void sendStatus(CommandSender sender) {
        sender.sendRichMessage("<gray>Full server maintenance:</gray> "
                + (maintenance.isActive() ? "<red><bold>ON</bold></red>" : "<green><bold>OFF</bold></green>")
                + " <dark_gray>•</dark_gray> <gray>Restricted online:</gray> <white>"
                + maintenance.restrictedOnlineCount() + "</white>");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("superduck.admin.maintenancemode")) return List.of();

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            return List.of("on", "off", "status", "setroom", "reload", "debug", "whitelist").stream()
                    .filter(option -> option.startsWith(input))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            String input = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            String input = args[1].toLowerCase(Locale.ROOT);
            return List.of("add", "remove", "list").stream()
                    .filter(option -> option.startsWith(input))
                    .toList();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            String input = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                    .toList();
        }

        return List.of();
    }
}
