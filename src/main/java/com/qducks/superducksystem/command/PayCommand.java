package com.qducks.superducksystem.command;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.EconomyService;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class PayCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckSystem plugin;
    private final MessageService messages;

    public PayCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (!plugin.configs().economy().getBoolean("payments.enabled", true)) {
            messages.send(player, "economy.pay-disabled", "<red>Player payments are currently disabled.</red>");
            return true;
        }
        if (args.length != 2) {
            messages.send(player, "economy.pay-usage", "<red>Usage: /pay <player> <amount></red>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(player, "errors.player-not-online", "<red>That player is not online.</red>");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.send(player, "economy.pay-self", "<red>You cannot pay yourself.</red>");
            return true;
        }

        BigDecimal amount;
        try {
            amount = plugin.economy().formatter().normalize(CurrencyType.MONEY, new BigDecimal(args[1].replace(",", "")));
        } catch (NumberFormatException exception) {
            messages.send(player, "errors.invalid-amount", "<red>That is not a valid amount.</red>");
            return true;
        }
        BigDecimal minimum = new BigDecimal(plugin.configs().economy().getString("payments.minimum", "1"));
        BigDecimal maximum = new BigDecimal(plugin.configs().economy().getString("payments.maximum", "1000000000000"));
        if (amount.compareTo(minimum) < 0 || amount.compareTo(maximum) > 0) {
            messages.send(player, "economy.pay-range", "<red>Payment must be between %minimum% and %maximum%.</red>", Map.of(
                    "minimum", plugin.economy().formatter().format(CurrencyType.MONEY, minimum),
                    "maximum", plugin.economy().formatter().format(CurrencyType.MONEY, maximum)
            ));
            return true;
        }

        try {
            plugin.economy().transfer(player.getUniqueId(), target.getUniqueId(), CurrencyType.MONEY, amount)
                    .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            Throwable cause = unwrap(error);
                            if (cause instanceof EconomyService.InsufficientFundsException) {
                                messages.send(player, "economy.insufficient", "<red>You do not have enough money.</red>");
                            } else {
                                messages.send(player, "economy.failed", "<red>Payment failed.</red>");
                            }
                            return;
                        }
                        String formatted = plugin.economy().formatter().format(CurrencyType.MONEY, result.amount());
                        messages.send(player, "economy.paid", "<green>You paid <white>%player%</white> %amount%.</green>",
                                Map.of("player", target.getName(), "amount", formatted));
                        messages.send(target, "economy.received", "<green>You received %amount% from <white>%player%</white>.</green>",
                                Map.of("player", player.getName(), "amount", formatted));
                    }));
        } catch (IllegalArgumentException exception) {
            messages.send(player, "errors.invalid-amount", "<red>That is not a valid amount.</red>");
        }
        return true;
    }

    private Throwable unwrap(Throwable throwable) {
        return throwable.getCause() == null ? throwable : throwable.getCause();
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase().startsWith(input)).toList();
        }
        return List.of();
    }
}
