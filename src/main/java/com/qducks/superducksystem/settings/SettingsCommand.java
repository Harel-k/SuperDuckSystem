package com.qducks.superducksystem.settings;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class SettingsCommand implements CommandExecutor {
    private final SettingsMenu menu;
    private final MessageService messages;

    public SettingsCommand(SuperDuckSystem plugin) {
        this.menu = new SettingsMenu(plugin);
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "errors.players-only", "<red>This command can only be used by players.</red>");
            return true;
        }
        menu.open(player);
        return true;
    }
}
