package com.qducks.superducksystem.maintenance;

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
            default -> sender.sendRichMessage("<red>Usage: /" + label + " [on|off|status|setroom|reload]</red>");
        }
        return true;
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
        if (args.length != 1) return List.of();
        String input = args[0].toLowerCase(Locale.ROOT);
        return List.of("on", "off", "status", "setroom", "reload").stream()
                .filter(option -> option.startsWith(input))
                .toList();
    }
}
