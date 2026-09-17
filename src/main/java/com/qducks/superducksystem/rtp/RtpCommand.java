package com.qducks.superducksystem.rtp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class RtpCommand implements CommandExecutor, TabCompleter {
    private final RtpService service;

    public RtpCommand(RtpService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("superduck.admin.rtp")) {
                sender.sendMessage("§cYou do not have permission to reload RTP.");
                return true;
            }
            service.reload();
            sender.sendMessage("§aRTP configuration reloaded.");
            return true;
        }

        if (args.length > 0) {
            sender.sendMessage("§7Usage: §f/rtp");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /rtp. Use /rtp reload from console.");
            return true;
        }

        if (!player.hasPermission("superduck.rtp")) {
            player.sendMessage("§cYou do not have permission to use /rtp.");
            return true;
        }

        service.begin(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("superduck.admin.rtp")
                && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }
}
