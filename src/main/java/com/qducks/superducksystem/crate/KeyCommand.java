package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

public final class KeyCommand implements CommandExecutor {
    private final SuperDuckSystem plugin;
    private final KeyService service;
    private final KeyMenu menu;
    private final MessageService messages;

    public KeyCommand(SuperDuckSystem plugin, KeyService service) {
        this.plugin = plugin;
        this.service = service;
        this.menu = new KeyMenu(plugin, service);
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "errors.players-only", "<red>This command can only be used by players.</red>");
                return true;
            }
            menu.open(player);
            return true;
        }

        if (!args[0].equalsIgnoreCase("give")) {
            messages.send(sender, "keys.usage", "<red>Usage: /key or /key give <player> <key> <amount></red>");
            return true;
        }
        if (!sender.hasPermission("superduck.admin.keys")) {
            messages.send(sender, "errors.no-permission", "<red>You do not have permission to do that.</red>");
            return true;
        }
        if (args.length != 4) {
            messages.send(sender, "keys.give-usage", "<red>Usage: /key give <player> <key> <amount></red>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "errors.player-not-online", "<red>That player is not online.</red>");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[3]);
            if (amount <= 0 || amount > 1000000) {
                throw new NumberFormatException("outside range");
            }
        } catch (NumberFormatException exception) {
            messages.send(sender, "keys.invalid-amount", "<red>Key amount must be a positive whole number.</red>");
            return true;
        }

        String keyId = args[2].toLowerCase(Locale.ROOT);
        service.giveKeys(target.getUniqueId(), keyId, amount).whenComplete((newBalance, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        messages.send(sender, "keys.invalid-key", "<red>That key does not exist.</red>");
                        return;
                    }
                    messages.send(sender, "keys.given", "<green>Gave <white>%amount%x %key%</white> to <white>%player%</white>.</green>", Map.of(
                            "amount", Integer.toString(amount),
                            "key", service.keyDisplayName(keyId),
                            "player", target.getName(),
                            "balance", Integer.toString(newBalance)
                    ));
                    messages.send(target, "keys.received", "<gold>You received <white>%amount%x %key%</white>.</gold>", Map.of(
                            "amount", Integer.toString(amount),
                            "key", service.keyDisplayName(keyId),
                            "balance", Integer.toString(newBalance)
                    ));
                })
        );
        return true;
    }
}
