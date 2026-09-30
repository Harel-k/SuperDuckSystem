package com.qducks.clientguard.command;

import com.qducks.clientguard.SuperDuckClientGuard;
import com.qducks.clientguard.detect.ClientScanner;
import com.qducks.clientguard.policy.PolicyEngine;
import com.qducks.clientguard.sanction.SanctionService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClientGuardCommand implements CommandExecutor, TabCompleter {
    private final SuperDuckClientGuard plugin;
    private final ClientScanner scanner;
    private final PolicyEngine policy;
    private final SanctionService sanctions;

    public ClientGuardCommand(SuperDuckClientGuard plugin, ClientScanner scanner, PolicyEngine policy,
                              SanctionService sanctions) {
        this.plugin = plugin;
        this.scanner = scanner;
        this.policy = policy;
        this.sanctions = sanctions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("superduck.clientguard.admin")) {
            sender.sendRichMessage("<red>You do not have permission.</red>");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            boolean enforcement = plugin.getConfig().getBoolean("enforcement.enabled", false);
            sender.sendRichMessage("<dark_aqua>ClientGuard</dark_aqua> <gray>scanner:</gray> "
                    + (plugin.getConfig().getBoolean("enabled", true) ? "<green>ON</green>" : "<red>OFF</red>")
                    + " <gray>| enforcement:</gray> "
                    + (enforcement ? "<yellow>ON</yellow>" : "<green>SAFE/MONITOR</green>")
                    + " <gray>| hard-sanction-ready:</gray> "
                    + (sanctions.ready() ? "<green>YES</green>" : "<red>NO</red>"));
            if (!sanctions.ready()) {
                for (String blocker : sanctions.blockers()) {
                    sender.sendRichMessage("<red> - " + blocker.replace("<", "\\<") + "</red>");
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            scanner.reloadDefinitions();
            sender.sendRichMessage("<green>ClientGuard configuration reloaded.</green>");
            return true;
        }

        if (args.length < 2) {
            sender.sendRichMessage("<yellow>Usage: /clientguard <scan|strikes|forgive> <player></yellow>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendRichMessage("<red>That player must be online.</red>");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> {
                scanner.scanAll(target);
                sender.sendRichMessage("<green>Started ClientGuard scan for <white>" + target.getName() + "</white>.</green>");
            }
            case "strikes" -> sender.sendRichMessage("<yellow>" + target.getName()
                    + "</yellow> <gray>Freecam strikes:</gray> <white>"
                    + policy.freecamStrikes(target) + "</white>");
            case "forgive" -> {
                policy.forgive(target);
                sender.sendRichMessage("<green>Cleared ClientGuard strikes for <white>"
                        + target.getName() + "</white>.</green>");
            }
            default -> sender.sendRichMessage("<yellow>Usage: /clientguard <status|scan|reload|strikes|forgive></yellow>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "scan", "reload", "strikes", "forgive").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && List.of("scan", "strikes", "forgive").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return List.of();
    }
}
