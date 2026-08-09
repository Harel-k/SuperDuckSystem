package com.qducks.superducksystem.command;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class DucksCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckSystem plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public DucksCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Usage: /ducks <player>");
                return true;
            }
            target = player;
        } else {
            if (!sender.hasPermission("superduck.ducks.others")) {
                sender.sendMessage(mini.deserialize("<red>You do not have permission to view other players' Ducks.</red>"));
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(mini.deserialize("<red>That player is not online.</red>"));
                return true;
            }
        }

        plugin.economy().balance(target.getUniqueId(), CurrencyType.DUCKS).whenComplete((balance, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendMessage(mini.deserialize("<red>Could not load Ducks right now.</red>"));
                        return;
                    }
                    String formatted = plugin.economy().formatter().format(CurrencyType.DUCKS, balance);
                    if (target.equals(sender)) {
                        sender.sendMessage(mini.deserialize("<gray>Ducks:</gray> <yellow>" + formatted + "</yellow>"));
                    } else {
                        sender.sendMessage(mini.deserialize("<white>" + target.getName() + "</white><gray>'s Ducks:</gray> <yellow>" + formatted + "</yellow>"));
                    }
                }));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("superduck.ducks.others")) {
            String input = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase().startsWith(input)).toList();
        }
        return List.of();
    }
}
