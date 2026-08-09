package com.qducks.superducksystem.command;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class BalanceTopCommand implements CommandExecutor {
    private final SuperDuckSystem plugin;
    private final MessageService messages;

    public BalanceTopCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        int size = Math.max(1, Math.min(plugin.configs().economy().getInt("leaderboards.balance-size", 10), 100));
        plugin.economy().topBalances(CurrencyType.MONEY, size).whenComplete((entries, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        messages.send(sender, "errors.database", "<red>Could not load that right now.</red>");
                        return;
                    }
                    messages.send(sender, "economy.baltop-header", "<aqua><bold>Top Balances</bold></aqua>");
                    if (entries.isEmpty()) {
                        messages.send(sender, "economy.baltop-empty", "<gray>No balances to display yet.</gray>");
                        return;
                    }
                    for (int index = 0; index < entries.size(); index++) {
                        var entry = entries.get(index);
                        messages.send(sender, "economy.baltop-line", "<yellow>#%rank%</yellow> <white>%player%</white> <gray>-</gray> <green>%balance%</green>", Map.of(
                                "rank", Integer.toString(index + 1),
                                "player", entry.username(),
                                "balance", plugin.economy().formatter().format(CurrencyType.MONEY, entry.amount())
                        ));
                    }
                }));
        return true;
    }
}
