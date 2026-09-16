package com.qducks.superducksystem.adminmode;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AdminModeCommand implements CommandExecutor, TabCompleter {
    private final AdminModeService service;

    public AdminModeCommand(AdminModeService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                sender.sendMessage("§6§lABUSE MODE §8» §7Current mode: " + (service.isActive(player) ? "§cABUSE" : "§aLEGIT"));
            }
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission(AdminModeService.OTHERS_PERMISSION)) {
                sender.sendMessage("§cYou do not have permission to reload Abuse Mode.");
                return true;
            }
            service.reload();
            sender.sendMessage("§a§lABUSE MODE §8» §7Configuration reloaded.");
            return true;
        }

        if (!sub.equals("on") && !sub.equals("off") && !sub.equals("status")) {
            sendUsage(sender);
            return true;
        }

        Player target = resolveTarget(sender, args);
        if (target == null) return true;

        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (self) {
            if (!sender.hasPermission(AdminModeService.TOGGLE_PERMISSION)) {
                sender.sendMessage("§cYou do not have permission to use Abuse Mode.");
                return true;
            }
        } else if (!sender.hasPermission(AdminModeService.OTHERS_PERMISSION)) {
            sender.sendMessage("§cYou do not have permission to control Abuse Mode for other players.");
            return true;
        }

        switch (sub) {
            case "on" -> service.enable(target, sender);
            case "off" -> service.disable(target, sender);
            case "status" -> {
                sender.sendMessage("§6§lABUSE MODE §8» §f" + target.getName());
                sender.sendMessage("§7Mode: " + (service.isActive(target) ? "§cABUSE" : "§aLEGIT"));
                sender.sendMessage("§7Switching: " + (service.isSwitching(target) ? "§eYES" : "§fNO"));
                sender.sendMessage("§7Profile file: §f" + service.profileFileForDebug(target.getUniqueId()).getAbsolutePath());
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cThat player must be online.");
                return null;
            }
            return target;
        }
        if (sender instanceof Player player) return player;
        sender.sendMessage("§cConsole usage: /abuse <on|off|status> <player>");
        return null;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6/abuse on §7[optional player]");
        sender.sendMessage("§6/abuse off §7[optional player]");
        sender.sendMessage("§6/abuse status §7[optional player]");
        if (sender.hasPermission(AdminModeService.OTHERS_PERMISSION)) sender.sendMessage("§6/abuse reload");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("on", "off", "status"));
            if (sender.hasPermission(AdminModeService.OTHERS_PERMISSION)) options.add("reload");
            return filter(options, args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload") && sender.hasPermission(AdminModeService.OTHERS_PERMISSION)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
