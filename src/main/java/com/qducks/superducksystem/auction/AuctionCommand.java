package com.qducks.superducksystem.auction;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Arrays;

public final class AuctionCommand implements CommandExecutor {
    private final AuctionMenu menu;
    private final MessageService messages;

    public AuctionCommand(SuperDuckSystem plugin, AuctionService service) {
        this.menu = new AuctionMenu(plugin, service);
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "errors.players-only", "<red>This command can only be used by players.</red>");
            return true;
        }

        if (args.length == 0) {
            menu.open(player, "", AuctionSort.NEWEST, 0);
            return true;
        }

        if (args[0].equalsIgnoreCase("sell")) {
            if (args.length != 2) {
                messages.send(player, "auction.sell-usage", "<red>Usage: /ah sell <price></red>");
                return true;
            }
            BigDecimal price;
            try {
                price = new BigDecimal(args[1].replace(",", ""));
            } catch (NumberFormatException exception) {
                messages.send(player, "errors.invalid-amount", "<red>That is not a valid amount.</red>");
                return true;
            }
            menu.startListing(player, price);
            return true;
        }

        String search = String.join(" ", Arrays.asList(args)).trim();
        menu.open(player, search, AuctionSort.NEWEST, 0);
        return true;
    }
}
