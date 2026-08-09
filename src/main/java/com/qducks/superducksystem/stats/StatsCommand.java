package com.qducks.superducksystem.stats;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class StatsCommand implements CommandExecutor {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final SuperDuckSystem plugin;

    public StatsCommand(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        OfflinePlayer target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendRichMessage("<red>Usage: /stats <player></red>");
                return true;
            }
            target = player;
        } else if (args.length == 1) {
            target = Bukkit.getOfflinePlayer(args[0]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                sender.sendRichMessage("<red>That player has never joined.</red>");
                return true;
            }
        } else {
            sender.sendRichMessage("<red>Usage: /stats [player]</red>");
            return true;
        }

        OfflinePlayer resolved = target;
        plugin.stats().profile(target.getUniqueId()).whenComplete((profile, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendRichMessage("<red>Could not load those stats right now.</red>");
                        return;
                    }
                    String name = resolved.getName() == null ? resolved.getUniqueId().toString() : resolved.getName();
                    StatsService.Snapshot stats = profile.base();
                    String title = plugin.configs().stats().getString("stats.title", "<aqua><bold>%player%'s Stats</bold></aqua>")
                            .replace("%player%", escape(name));
                    sender.sendMessage(MINI.deserialize(title));
                    for (String raw : plugin.configs().stats().getStringList("stats.lines")) {
                        sender.sendMessage(MINI.deserialize(replace(raw, name, stats, profile)));
                    }
                })
        );
        return true;
    }

    private String replace(String raw, String player, StatsService.Snapshot stats, StatsService.Profile profile) {
        return raw
                .replace("%player%", escape(player))
                .replace("%kills%", Long.toString(stats.kills()))
                .replace("%deaths%", Long.toString(stats.deaths()))
                .replace("%kd%", String.format(Locale.ROOT, "%.2f", stats.kd()))
                .replace("%playtime%", formatTime(stats.playtimeSeconds()))
                .replace("%playtime_seconds%", Long.toString(stats.playtimeSeconds()))
                .replace("%crates%", Long.toString(stats.cratesOpened()))
                .replace("%keys_used%", Long.toString(stats.keysUsed()))
                .replace("%auctions_sold%", Long.toString(profile.auctionsSold()))
                .replace("%orders_created%", Long.toString(profile.ordersCreated()))
                .replace("%orders_filled%", Long.toString(profile.ordersFilled()))
                .replace("%items_sold_orders%", Long.toString(profile.itemsSoldIntoOrders()))
                .replace("%money_earned%", plugin.economy().formatter().format(CurrencyType.MONEY, profile.moneyEarned()))
                .replace("%money_spent%", plugin.economy().formatter().format(CurrencyType.MONEY, profile.moneySpent()))
                .replace("%money_sent%", plugin.economy().formatter().format(CurrencyType.MONEY, profile.moneySent()))
                .replace("%money_received%", plugin.economy().formatter().format(CurrencyType.MONEY, profile.moneyReceived()))
                .replace("%sell_earned%", plugin.economy().formatter().format(CurrencyType.MONEY, profile.sellEarned()))
                .replace("%auction_earned%", plugin.economy().formatter().format(CurrencyType.MONEY, profile.auctionEarned()))
                .replace("%ducks_earned%", plugin.economy().formatter().format(CurrencyType.DUCKS, profile.ducksEarned()))
                .replace("%ducks_spent%", plugin.economy().formatter().format(CurrencyType.DUCKS, profile.ducksSpent()));
    }

    private String formatTime(long seconds) {
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("<", "\\<");
    }
}
