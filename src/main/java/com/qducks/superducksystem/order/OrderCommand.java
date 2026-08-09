package com.qducks.superducksystem.order;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public final class OrderCommand implements CommandExecutor {
    private final OrderMenu menu;
    private final MessageService messages;

    public OrderCommand(SuperDuckSystem plugin, OrderService service) {
        this.menu = new OrderMenu(plugin, service);
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "errors.players-only", "<red>This command can only be used by players.</red>");
            return true;
        }

        if (args.length == 0) {
            menu.open(player, "", OrderSort.NEWEST, 0);
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            menu.beginCreate(player);
            return true;
        }

        String search = String.join(" ", Arrays.asList(args)).trim();
        menu.open(player, search, OrderSort.NEWEST, 0);
        return true;
    }
}
