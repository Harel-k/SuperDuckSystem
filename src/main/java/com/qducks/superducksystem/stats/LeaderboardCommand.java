package com.qducks.superducksystem.stats;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class LeaderboardCommand implements CommandExecutor, TabCompleter {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final SuperDuckSystem plugin;

    public LeaderboardCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        StatsService.Leaderboard type = StatsService.Leaderboard.MONEY;
        if (args.length > 1) {
            sender.sendRichMessage("<red>Usage: /leaderboard [money|ducks|kills|playtime|crates]</red>");
            return true;
        }
        if (args.length == 1) {
            try {
                type = StatsService.Leaderboard.valueOf(args[0].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                sender.sendRichMessage("<red>Unknown leaderboard. Use money, ducks, kills, playtime or crates.</red>");
                return true;
            }
        }

        int limit = Math.max(1, Math.min(100, plugin.configs().stats().getInt("leaderboard.default-size", 10)));
        StatsService.Leaderboard selected = type;
        plugin.stats().leaderboard(type, limit).whenComplete((entries, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendRichMessage("<red>Could not load the leaderboard right now.</red>");
                        return;
                    }
                    String displayType = pretty(selected.name());
                    String title = plugin.configs().stats().getString("leaderboard.title", "<gold><bold>%type% Leaderboard</bold></gold>")
                            .replace("%type%", displayType);
                    sender.sendMessage(MINI.deserialize(title));
                    if (entries.isEmpty()) {
                        sender.sendMessage(MINI.deserialize(plugin.configs().stats().getString("leaderboard.empty", "<gray>No leaderboard data yet.</gray>")));
                        return;
                    }
                    String line = plugin.configs().stats().getString("leaderboard.line",
                            "<yellow>#%rank%</yellow> <white>%player%</white> <gray>-</gray> <green>%value%</green>");
                    int rank = 1;
                    for (StatsService.LeaderboardEntry entry : entries) {
                        String value = formatValue(selected, entry.value());
                        sender.sendMessage(MINI.deserialize(line
                                .replace("%rank%", Integer.toString(rank++))
                                .replace("%player%", escape(entry.username()))
                                .replace("%value%", value)
                                .replace("%type%", displayType)));
                    }
                })
        );
        return true;
    }

    private String formatValue(StatsService.Leaderboard type, double value) {
        return switch (type) {
            case MONEY -> plugin.economy().formatter().format(CurrencyType.MONEY, java.math.BigDecimal.valueOf(value));
            case DUCKS -> plugin.economy().formatter().format(CurrencyType.DUCKS, java.math.BigDecimal.valueOf(value));
            case PLAYTIME -> formatTime((long) value);
            default -> Long.toString(Math.round(value));
        };
    }

    private String formatTime(long seconds) {
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private String pretty(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("<", "\\<");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String input = args[0].toLowerCase(Locale.ROOT);
        return Arrays.stream(StatsService.Leaderboard.values())
                .map(value -> value.name().toLowerCase(Locale.ROOT))
                .filter(value -> value.startsWith(input)).toList();
    }
}
