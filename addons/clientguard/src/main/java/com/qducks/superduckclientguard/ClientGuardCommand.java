package com.qducks.superduckclientguard;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ClientGuardCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckClientGuard plugin;

    ClientGuardCommand(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("superduck.clientguard.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("/" + label + " scan <player>");
            sender.sendMessage("/" + label + " status <player>");
            sender.sendMessage("/" + label + " reload");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /" + label + " scan <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("That player is not online.");
                    return true;
                }
                boolean started = plugin.scans().startScan(target, "manual:" + sender.getName());
                sender.sendMessage(started
                        ? "ClientGuard scan started for " + target.getName() + "."
                        : "Could not start scan (already checking, Bedrock, disabled, or no checks configured).");
            }
            case "status" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /" + label + " status <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player must be online for this status command.");
                    return true;
                }
                sender.sendMessage("Freecam strikes for " + target.getName() + ": "
                        + plugin.strikes().freecamStrikes(target.getUniqueId()));
                sender.sendMessage("Currently scanning: "
                        + plugin.scans().isChecking(target.getUniqueId()));
            }
            case "reload" -> {
                plugin.reloadAddonConfig();
                sender.sendMessage("SuperDuckClientGuard configuration reloaded.");
            }
            default -> sender.sendMessage("Unknown subcommand. Use /" + label + ".");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("scan", "status", "reload");
        if (args.length == 2 && (args[0].equalsIgnoreCase("scan") || args[0].equalsIgnoreCase("status"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return names;
        }
        return List.of();
    }
}
