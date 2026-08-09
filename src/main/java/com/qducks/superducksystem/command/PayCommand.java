package com.qducks.superducksystem.command;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.EconomyService;
import net.kyori.adventure.text.minimessage.MiniMessage;
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

public final class PayCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckSystem plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public PayCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (!plugin.configs().economy().getBoolean("payments.enabled", true)) {
            send(player, "<red>Player payments are currently disabled.</red>");
            return true;
        }
        if (args.length != 2) {
            send(player, "<red>Usage: /pay <player> <amount></red>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(player, "<red>That player is not online.</red>");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(player, "<red>You cannot pay yourself.</red>");
            return true;
        }

        BigDecimal amount;
        try {
            amount = plugin.economy().formatter().normalize(CurrencyType.MONEY, new BigDecimal(args[1].replace(",", "")));
        } catch (NumberFormatException exception) {
            send(player, "<red>That is not a valid amount.</red>");
            return true;
        }
        BigDecimal minimum = new BigDecimal(plugin.configs().economy().getString("payments.minimum", "1"));
        BigDecimal maximum = new BigDecimal(plugin.configs().economy().getString("payments.maximum", "1000000000000"));
        if (amount.compareTo(minimum) < 0 || amount.compareTo(maximum) > 0) {
            send(player, "<red>Payment must be between " + plugin.economy().formatter().format(CurrencyType.MONEY, minimum)
                    + " and " + plugin.economy().formatter().format(CurrencyType.MONEY, maximum) + ".</red>");
            return true;
        }

        try {
            plugin.economy().transfer(player.getUniqueId(), target.getUniqueId(), CurrencyType.MONEY, amount)
                    .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            Throwable cause = unwrap(error);
                            if (cause instanceof EconomyService.InsufficientFundsException) {
                                send(player, "<red>You do not have enough money.</red>");
                            } else {
                                send(player, "<red>Payment failed: " + safe(cause.getMessage()) + "</red>");
                            }
                            return;
                        }
                        String formatted = plugin.economy().formatter().format(CurrencyType.MONEY, result.amount());
                        send(player, "<green>You paid <white>" + target.getName() + "</white> " + formatted + ".</green>");
                        send(target, "<green>You received " + formatted + " from <white>" + player.getName() + "</white>.</green>");
                    }));
        } catch (IllegalArgumentException exception) {
            send(player, "<red>" + safe(exception.getMessage()) + ".</red>");
        }
        return true;
    }

    private Throwable unwrap(Throwable throwable) {
        return throwable.getCause() == null ? throwable : throwable.getCause();
    }

    private String safe(String value) {
        return value == null ? "unknown error" : value.replace("<", "");
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(mini.deserialize(text));
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
