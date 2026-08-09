package com.qducks.superducksystem.integration.placeholder;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.stats.StatsService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Locale;

public final class SuperDuckExpansion extends PlaceholderExpansion {
    private final SuperDuckSystem plugin;

    public SuperDuckExpansion(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "superduck";
    }

    @Override
    public @NotNull String getAuthor() {
        return "QDucks";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "server_name" -> plugin.configs().serverName();
            case "version" -> plugin.getPluginMeta().getVersion();
            case "database_ready" -> Boolean.toString(plugin.database().isReady());
            case "bedrock" -> {
                Player online = player == null ? null : player.getPlayer();
                yield Boolean.toString(online != null && plugin.integrations().bedrock().isBedrock(online));
            }
            case "balance", "balance_raw" -> raw(player, CurrencyType.MONEY);
            case "balance_formatted" -> formatted(player, CurrencyType.MONEY);
            case "ducks", "ducks_raw" -> raw(player, CurrencyType.DUCKS);
            case "ducks_formatted" -> formatted(player, CurrencyType.DUCKS);
            case "kills" -> stat(player, StatValue.KILLS);
            case "deaths" -> stat(player, StatValue.DEATHS);
            case "kd" -> stat(player, StatValue.KD);
            case "playtime", "playtime_seconds" -> stat(player, StatValue.PLAYTIME);
            case "crates_opened" -> stat(player, StatValue.CRATES);
            case "keys_used" -> stat(player, StatValue.KEYS_USED);
            default -> null;
        };
    }

    private String raw(OfflinePlayer player, CurrencyType currency) {
        if (player == null) {
            return "0";
        }
        BigDecimal value = plugin.economy().cachedBalance(player.getUniqueId(), currency);
        return value.stripTrailingZeros().toPlainString();
    }

    private String formatted(OfflinePlayer player, CurrencyType currency) {
        if (player == null) {
            return plugin.economy().formatter().format(currency, BigDecimal.ZERO);
        }
        return plugin.economy().formatter().format(currency, plugin.economy().cachedBalance(player.getUniqueId(), currency));
    }

    private String stat(OfflinePlayer player, StatValue value) {
        if (player == null) return "0";
        StatsService.Snapshot stats = plugin.stats().cached(player.getUniqueId());
        return switch (value) {
            case KILLS -> Long.toString(stats.kills());
            case DEATHS -> Long.toString(stats.deaths());
            case KD -> String.format(Locale.ROOT, "%.2f", stats.kd());
            case PLAYTIME -> Long.toString(stats.playtimeSeconds());
            case CRATES -> Long.toString(stats.cratesOpened());
            case KEYS_USED -> Long.toString(stats.keysUsed());
        };
    }

    private enum StatValue {
        KILLS, DEATHS, KD, PLAYTIME, CRATES, KEYS_USED
    }
}
