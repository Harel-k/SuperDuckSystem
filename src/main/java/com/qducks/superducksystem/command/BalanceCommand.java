package com.qducks.superducksystem.command;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public final class BalanceCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckSystem plugin;
    private final MessageService messages;

    public BalanceCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "economy.balance-usage", "<red>Usage: /balance <player></red>");
                return true;
            }
            target = player;
        } else {
            if (!sender.hasPermission("superduck.balance.others")) {
                messages.send(sender, "errors.no-permission", "<red>You do not have permission to do that.</red>");
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                messages.send(sender, "errors.player-not-online", "<red>That player is not online.</red>");
                return true;
            }
        }

        plugin.economy().balance(target.getUniqueId(), CurrencyType.MONEY).whenComplete((balance, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        messages.send(sender, "errors.database", "<red>Could not load that right now.</red>");
                        return;
                    }
                    String formatted = plugin.economy().formatter().format(CurrencyType.MONEY, balance);
                    if (target.equals(sender)) {
                        messages.send(sender, "economy.balance", "<gray>Balance:</gray> <green>%balance%</green>", Map.of("balance", formatted));
                    } else {
                        messages.send(sender, "economy.balance-other", "<white>%player%</white><gray>'s balance:</gray> <green>%balance%</green>",
                                Map.of("player", target.getName(), "balance", formatted));
                    }
                }));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("superduck.balance.others")) {
            String input = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase().startsWith(input)).toList();
        }
        return List.of();
    }
}
