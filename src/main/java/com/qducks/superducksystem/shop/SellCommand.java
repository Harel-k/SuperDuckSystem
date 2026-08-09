package com.qducks.superducksystem.shop;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class SellCommand implements CommandExecutor {
    private final SuperDuckSystem plugin;
    private final SellMenu menu;
    private final MessageService messages;

    public SellCommand(SuperDuckSystem plugin, ShopService shop) {
        this.plugin = plugin;
        this.menu = new SellMenu(plugin, shop);
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "errors.players-only", "<red>This command can only be used by players.</red>");
            return true;
        }
        if (plugin.state().maintenance("shop")) {
            player.sendRichMessage("<red>Selling is temporarily in maintenance mode.</red>");
            return true;
        }
        menu.open(player);
        return true;
    }
}
