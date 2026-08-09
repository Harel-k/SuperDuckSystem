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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SuperDuckCommand implements CommandExecutor, TabCompleter {
    private static final List<String> MAINTENANCE_MODULES = List.of("economy", "shop", "auctions", "orders", "crates", "rewards", "custom-tools");
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
            case "readonly" -> readOnly(sender, args);
            case "maintenance" -> maintenance(sender, args);
            case "backup" -> backup(sender);
            default -> {
                sender.sendMessage(message("errors.unknown-subcommand", "<red>Unknown subcommand.</red>", label));
                yield true;
            }
        };
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission("superduck.admin.status")) return denied(sender);
        sender.sendMessage(message("admin.status-header", "<aqua><bold>SuperDuckSystem</bold></aqua> <gray>Status</gray>", "superduck"));
        sender.sendMessage(miniMessage.deserialize("<gray>Version:</gray> <white>" + plugin.getPluginMeta().getVersion() + "</white>"));
        sender.sendMessage(miniMessage.deserialize("<gray>Server:</gray> <white>" + escape(plugin.configs().serverName()) + "</white>"));
        sender.sendMessage(miniMessage.deserialize("<gray>Database:</gray> " + ready(plugin.database().isReady())));
        sender.sendMessage(miniMessage.deserialize("<gray>Stats:</gray> " + ready(plugin.stats().ready())));
        sender.sendMessage(miniMessage.deserialize("<gray>Rewards:</gray> " + ready(plugin.rewards().ready())));
        sender.sendMessage(miniMessage.deserialize("<gray>Digital keys:</gray> " + ready(plugin.keys().ready())));
        sender.sendMessage(miniMessage.deserialize("<gray>Floodgate:</gray> " + ready(plugin.integrations().bedrock().available())));
        sender.sendMessage(miniMessage.deserialize("<gray>LuckPerms:</gray> " + ready(plugin.integrations().ranks().available())));
        sender.sendMessage(miniMessage.deserialize("<gray>VaultUnlocked provider:</gray> " + ready(plugin.integrations().vaultUnlocked())));
        sender.sendMessage(miniMessage.deserialize("<gray>Read-only:</gray> " + (plugin.state().readOnly() ? "<red>ON</red>" : "<green>OFF</green>")));
        String maintenance = plugin.state().maintenanceModules().isEmpty() ? "<green>none</green>"
                : "<yellow>" + escape(String.join(", ", plugin.state().maintenanceModules())) + "</yellow>";
        sender.sendMessage(miniMessage.deserialize("<gray>Maintenance:</gray> " + maintenance));
        sender.sendMessage(miniMessage.deserialize("<gray>Registered modules:</gray> <white>" + plugin.modules().all().size() + "</white>"));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("superduck.admin.reload")) return denied(sender);
        plugin.configs().reload();
        plugin.rankPerks().reloadOnlinePlayers();
        plugin.modules().reload();
        sender.sendMessage(message("admin.reloaded", "<green>SuperDuckSystem configuration reloaded.</green>", "superduck"));
        return true;
    }

    private boolean readOnly(CommandSender sender, String[] args) {
        if (!sender.hasPermission("superduck.admin.emergency")) return denied(sender);
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
            sender.sendRichMessage("<gray>SuperDuck read-only mode:</gray> " + (plugin.state().readOnly() ? "<red>ON</red>" : "<green>OFF</green>"));
            return true;
        }
        if (args.length != 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            sender.sendRichMessage("<red>Usage: /sds readonly <on|off|status></red>");
            return true;
        }
        boolean enabled = args[1].equalsIgnoreCase("on");
        plugin.state().setReadOnly(enabled);
        sender.sendRichMessage(enabled
                ? "<red><bold>SuperDuck read-only mode ENABLED.</bold></red> <gray>Player economy-changing actions are frozen.</gray>"
                : "<green><bold>SuperDuck read-only mode disabled.</bold></green>");
        return true;
    }

    private boolean maintenance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("superduck.admin.emergency")) return denied(sender);
        if (args.length != 3 || (!args[2].equalsIgnoreCase("on") && !args[2].equalsIgnoreCase("off"))) {
            sender.sendRichMessage("<red>Usage: /sds maintenance <module> <on|off></red>");
            return true;
        }
        String module = args[1].toLowerCase(Locale.ROOT);
        if (!MAINTENANCE_MODULES.contains(module)) {
            sender.sendRichMessage("<red>Unknown module. Use: <white>" + String.join(", ", MAINTENANCE_MODULES) + "</white></red>");
            return true;
        }
        boolean enabled = args[2].equalsIgnoreCase("on");
        plugin.state().setMaintenance(module, enabled);
        sender.sendRichMessage("<gray>Maintenance for <white>" + module + "</white>:</gray> " + (enabled ? "<red>ON</red>" : "<green>OFF</green>"));
        return true;
    }

    private boolean backup(CommandSender sender) {
        if (!sender.hasPermission("superduck.admin.backup")) return denied(sender);
        sender.sendRichMessage("<yellow>Creating a consistent SuperDuck database backup...</yellow>");
        plugin.database().backup().whenComplete((file, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                sender.sendRichMessage("<red>Backup failed: <white>" + escape(rootMessage(error)) + "</white></red>");
                return;
            }
            sender.sendRichMessage("<green>Backup created:</green> <white>plugins/SuperDuckSystem/backups/" + escape(file.getName()) + "</white>");
        }));
        return true;
    }

    private boolean giveItem(CommandSender sender, String[] args) {
        if (!sender.hasPermission("superduck.admin.items")) return denied(sender);
        if (args.length < 3 || args.length > 4) {
            sender.sendRichMessage("<red>Usage: /sds giveitem <player> <item-id> [amount]</red>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendRichMessage("<red>That player is not online.</red>");
            return true;
        }
        int amount = 1;
        if (args.length == 4) {
            try { amount = Integer.parseInt(args[3]); }
            catch (NumberFormatException exception) {
                sender.sendRichMessage("<red>Amount must be a positive whole number.</red>"); return true;
            }
        }
        if (amount <= 0 || amount > 64) {
            sender.sendRichMessage("<red>Amount must be between 1 and 64.</red>"); return true;
        }
        ItemStack item = plugin.customItems().createConfigured(args[2], amount);
        if (item == null) {
            sender.sendRichMessage("<red>Unknown custom item: <white>" + escape(args[2]) + "</white>.</red>"); return true;
        }
        target.getInventory().addItem(item).values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        sender.sendRichMessage("<green>Gave <white>" + item.getAmount() + "x " + escape(args[2]) + "</white> to <white>" + escape(target.getName()) + "</white>.</green>");
        return true;
    }

    private boolean denied(CommandSender sender) {
        sender.sendMessage(message("errors.no-permission", "<red>You do not have permission to do that.</red>", "superduck"));
        return true;
    }

    private String ready(boolean ready) { return ready ? "<green>READY</green>" : "<gray>OFF/STARTING</gray>"; }

    private Component message(String path, String fallback, String label) {
        String raw = plugin.configs().messages().getString(path, fallback);
        raw = raw.replace("%version%", plugin.getPluginMeta().getVersion()).replace("%server_name%", plugin.configs().serverName()).replace("%label%", label);
        return miniMessage.deserialize(raw);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String escape(String text) { return text == null ? "" : text.replace("<", "\\<"); }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("superduck.admin.status")) options.add("status");
            if (sender.hasPermission("superduck.admin.reload")) options.add("reload");
            if (sender.hasPermission("superduck.admin.items")) options.add("giveitem");
            if (sender.hasPermission("superduck.admin.emergency")) { options.add("readonly"); options.add("maintenance"); }
            if (sender.hasPermission("superduck.admin.backup")) options.add("backup");
            String input = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(input)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("giveitem") && sender.hasPermission("superduck.admin.items")) {
            String input = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("giveitem") && sender.hasPermission("superduck.admin.items")) {
            ConfigurationSection items = plugin.configs().customItems().getConfigurationSection("items");
            if (items == null) return List.of();
            String input = args[2].toLowerCase(Locale.ROOT);
            return items.getKeys(false).stream().filter(id -> id.toLowerCase(Locale.ROOT).startsWith(input)).sorted().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("readonly")) return List.of("on", "off", "status").stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("maintenance")) return MAINTENANCE_MODULES.stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("maintenance")) return List.of("on", "off").stream().filter(v -> v.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
