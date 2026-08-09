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

public final class BalanceCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckSystem plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public BalanceCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Usage: /balance <player>");
                return true;
            }
            target = player;
        } else {
            if (!sender.hasPermission("superduck.balance.others")) {
                send(sender, "<red>You do not have permission to view other balances.</red>");
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                send(sender, "<red>That player is not online.</red>");
                return true;
            }
        }

        plugin.economy().balance(target.getUniqueId(), CurrencyType.MONEY).whenComplete((balance, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        send(sender, "<red>Could not load the balance right now.</red>");
                        return;
                    }
                    String formatted = plugin.economy().formatter().format(CurrencyType.MONEY, balance);
                    if (target.equals(sender)) {
                        send(sender, "<gray>Balance:</gray> <green>" + formatted + "</green>");
                    } else {
                        send(sender, "<white>" + target.getName() + "</white><gray>'s balance:</gray> <green>" + formatted + "</green>");
                    }
                }));
        return true;
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(mini.deserialize(text));
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
