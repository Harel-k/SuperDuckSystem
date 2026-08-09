package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.message.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class CrateCommand implements CommandExecutor {
    private final CrateMenu menu;
    private final CrateService service;
    private final MessageService messages;

    public CrateCommand(SuperDuckSystem plugin, KeyService keys, CrateService service) {
        this.menu = new CrateMenu(plugin, keys, service);
        this.service = service;
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "errors.players-only", "<red>This command can only be used by players.</red>");
            return true;
        }

        if (args.length == 0) {
            menu.open(player);
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("preview")) {
            String crateId = args[1].toLowerCase(Locale.ROOT);
            if (!service.configuredCrates().stream().anyMatch(id -> id.equalsIgnoreCase(crateId))) {
                messages.send(player, "crates.invalid", "<red>That crate does not exist.</red>");
                return true;
            }
            menu.preview(player, crateId);
            return true;
        }

        if (args.length == 1) {
            String crateId = args[0].toLowerCase(Locale.ROOT);
            if (!service.configuredCrates().stream().anyMatch(id -> id.equalsIgnoreCase(crateId))) {
                messages.send(player, "crates.invalid", "<red>That crate does not exist.</red>");
                return true;
            }
            menu.openCrate(player, crateId);
            return true;
        }

        messages.send(player, "crates.usage", "<red>Usage: /crates [crate] or /crates preview <crate></red>");
        return true;
    }
}
