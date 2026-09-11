package com.qducks.superducksystem.maintenance;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class MaintenanceCommand implements CommandExecutor, TabCompleter {
    private final MaintenanceService maintenance;

    public MaintenanceCommand(MaintenanceService maintenance) {
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
                maintenance.reload();
                maintenance.reconcileOnlinePlayers();
                sender.sendRichMessage("<green>Maintenance configuration reloaded.</green>");
                sendStatus(sender);
            }
            case "debug" -> debug(sender, args);
            default -> sender.sendRichMessage("<red>Usage: /" + label + " [on|off|status|setroom|reload|debug]</red>");
        }
        return true;
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

        sender.sendRichMessage("<gold><bold>Maintenance Debug</bold></gold> <gray>for</gray> <white>" + target.getName() + "</white>");
        sender.sendRichMessage("<gray>Mode active:</gray> " + yesNo(maintenance.isActive()));
        sender.sendRichMessage("<gray>OP:</gray> " + yesNo(target.isOp()));
        sender.sendRichMessage("<gray>Admin command permission:</gray> "
                + yesNo(target.hasPermission("superduck.admin.maintenancemode")));
        sender.sendRichMessage("<gray>Whitelist permission <white>" + maintenance.whitelistPermissionNode()
                + "</white>:</gray> " + yesNo(maintenance.hasWhitelistPermission(target)));
        sender.sendRichMessage("<gray>Listed in maintenance.yml:</gray> " + yesNo(maintenance.isListedInConfig(target)));
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
            return List.of("on", "off", "status", "setroom", "reload", "debug").stream()
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

        return List.of();
    }
}
