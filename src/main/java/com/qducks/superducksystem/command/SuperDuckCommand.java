package com.qducks.superducksystem.command;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class SuperDuckCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckSystem plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public SuperDuckCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(message("help.header", "<aqua><bold>SuperDuckSystem</bold></aqua> <gray>v%version%</gray>", label));
            sender.sendMessage(message("help.line", "<gray>Use <white>/%label% status</white> to view system status.</gray>", label));
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> {
                sender.sendMessage(message("errors.unknown-subcommand", "<red>Unknown subcommand.</red>", label));
                yield true;
            }
        };
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission("superduck.admin.status")) {
            sender.sendMessage(message("errors.no-permission", "<red>You do not have permission to do that.</red>", "superduck"));
            return true;
        }

        sender.sendMessage(message("admin.status-header", "<aqua><bold>SuperDuckSystem</bold></aqua> <gray>Status</gray>", "superduck"));
        sender.sendMessage(miniMessage.deserialize("<gray>Version:</gray> <white>" + plugin.getPluginMeta().getVersion() + "</white>"));
        sender.sendMessage(miniMessage.deserialize("<gray>Server:</gray> <white>" + escape(plugin.configs().serverName()) + "</white>"));
        sender.sendMessage(miniMessage.deserialize("<gray>Database:</gray> " + (plugin.database().isReady() ? "<green>READY</green>" : "<yellow>STARTING</yellow>")));
        sender.sendMessage(miniMessage.deserialize("<gray>Bedrock integration:</gray> " + (plugin.integrations().bedrock().available() ? "<green>FLOODGATE</green>" : "<gray>OFF</gray>")));
        sender.sendMessage(miniMessage.deserialize("<gray>LuckPerms rank perks:</gray> " + (plugin.integrations().ranks().available() ? "<green>READY</green>" : "<gray>FALLBACK</gray>")));
        sender.sendMessage(miniMessage.deserialize("<gray>Registered modules:</gray> <white>" + plugin.modules().all().size() + "</white>"));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("superduck.admin.reload")) {
            sender.sendMessage(message("errors.no-permission", "<red>You do not have permission to do that.</red>", "superduck"));
            return true;
        }
        plugin.configs().reload();
        plugin.rankPerks().reloadOnlinePlayers();
        sender.sendMessage(message("admin.reloaded", "<green>SuperDuckSystem configuration reloaded.</green>", "superduck"));
        return true;
    }

    private Component message(String path, String fallback, String label) {
        String raw = plugin.configs().messages().getString(path, fallback);
        raw = raw.replace("%version%", plugin.getPluginMeta().getVersion())
                .replace("%server_name%", plugin.configs().serverName())
                .replace("%label%", label);
        return miniMessage.deserialize(raw);
    }

    private String escape(String text) {
        return text.replace("<", "\\<");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        if (sender.hasPermission("superduck.admin.status")) {
            options.add("status");
        }
        if (sender.hasPermission("superduck.admin.reload")) {
            options.add("reload");
        }
        String input = args[0].toLowerCase();
        return options.stream().filter(option -> option.startsWith(input)).toList();
    }
}
