package com.qducks.superducksystem.command;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            case "giveitem" -> giveItem(sender, args);
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
        plugin.modules().reload();
        sender.sendMessage(message("admin.reloaded", "<green>SuperDuckSystem configuration reloaded.</green>", "superduck"));
        return true;
    }

    private boolean giveItem(CommandSender sender, String[] args) {
        if (!sender.hasPermission("superduck.admin.items")) {
            sender.sendMessage(message("errors.no-permission", "<red>You do not have permission to do that.</red>", "superduck"));
            return true;
        }
        if (args.length < 3 || args.length > 4) {
            sender.sendMessage(miniMessage.deserialize("<red>Usage: /sds giveitem <player> <item-id> [amount]</red>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(miniMessage.deserialize("<red>That player is not online.</red>"));
            return true;
        }
        int amount = 1;
        if (args.length == 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(miniMessage.deserialize("<red>Amount must be a positive whole number.</red>"));
                return true;
            }
        }
        if (amount <= 0 || amount > 64) {
            sender.sendMessage(miniMessage.deserialize("<red>Amount must be between 1 and 64.</red>"));
            return true;
        }

        ItemStack item = plugin.customItems().createConfigured(args[2], amount);
        if (item == null) {
            sender.sendMessage(miniMessage.deserialize("<red>Unknown custom item: <white>" + escape(args[2]) + "</white>.</red>"));
            return true;
        }
        target.getInventory().addItem(item).values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        sender.sendMessage(miniMessage.deserialize("<green>Gave <white>" + item.getAmount() + "x " + escape(args[2])
                + "</white> to <white>" + escape(target.getName()) + "</white>.</green>"));
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
        return text == null ? "" : text.replace("<", "\\<");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("superduck.admin.status")) options.add("status");
            if (sender.hasPermission("superduck.admin.reload")) options.add("reload");
            if (sender.hasPermission("superduck.admin.items")) options.add("giveitem");
            String input = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(input)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("giveitem") && sender.hasPermission("superduck.admin.items")) {
            String input = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("giveitem") && sender.hasPermission("superduck.admin.items")) {
            ConfigurationSection items = plugin.configs().customItems().getConfigurationSection("items");
            if (items == null) return List.of();
            String input = args[2].toLowerCase(Locale.ROOT);
            return items.getKeys(false).stream().filter(id -> id.toLowerCase(Locale.ROOT).startsWith(input)).sorted().toList();
        }
        return List.of();
    }
}
