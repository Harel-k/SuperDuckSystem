package com.qducks.superducksystem.command;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.TransactionType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class EcoCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckSystem plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public EcoCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("superduck.admin.economy")) {
            send(sender, "<red>You do not have permission to do that.</red>");
            return true;
        }
        if (args.length < 2) {
            send(sender, "<red>Usage: /eco <give|take|set|reset> <player> [amount] [money|ducks]</red>");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            send(sender, "<red>That player has never joined the server.</red>");
            return true;
        }
        CurrencyType currency = args.length >= 4 && args[3].equalsIgnoreCase("ducks") ? CurrencyType.DUCKS : CurrencyType.MONEY;
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;

        if (action.equals("reset")) {
            plugin.economy().set(target.getUniqueId(), currency, plugin.economy().startingBalance(currency), TransactionType.ADMIN_RESET, actor)
                    .whenComplete((balance, error) -> finish(sender, target, currency, balance, error, "reset"));
            return true;
        }
        if (args.length < 3) {
            send(sender, "<red>You must provide an amount.</red>");
            return true;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(args[2].replace(",", ""));
        } catch (NumberFormatException exception) {
            send(sender, "<red>That is not a valid amount.</red>");
            return true;
        }

        var future = switch (action) {
            case "give" -> plugin.economy().add(target.getUniqueId(), currency, amount, TransactionType.ADMIN_GIVE, actor);
            case "take" -> plugin.economy().take(target.getUniqueId(), currency, amount, TransactionType.ADMIN_TAKE, actor);
            case "set" -> plugin.economy().set(target.getUniqueId(), currency, amount, TransactionType.ADMIN_SET, actor);
            default -> null;
        };
        if (future == null) {
            send(sender, "<red>Unknown action. Use give, take, set or reset.</red>");
            return true;
        }
        future.whenComplete((balance, error) -> finish(sender, target, currency, balance, error, action));
        return true;
    }

    private void finish(CommandSender sender, OfflinePlayer target, CurrencyType currency, BigDecimal balance, Throwable error, String action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                send(sender, "<red>Economy action failed: " + safe(cause.getMessage()) + "</red>");
                return;
            }
            String formatted = plugin.economy().formatter().format(currency, balance);
            send(sender, "<green>" + action + " completed for <white>" + (target.getName() == null ? target.getUniqueId() : target.getName()) + "</white>. New balance: " + formatted + "</green>");
        });
    }

    private String safe(String value) {
        return value == null ? "unknown error" : value.replace("<", "");
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(mini.deserialize(text));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("superduck.admin.economy")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("give", "take", "set", "reset").stream().filter(v -> v.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2) {
            String input = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(v -> v.toLowerCase().startsWith(input)).toList();
        }
        if (args.length == 4) {
            return List.of("money", "ducks").stream().filter(v -> v.startsWith(args[3].toLowerCase())).toList();
        }
        return List.of();
    }
}
