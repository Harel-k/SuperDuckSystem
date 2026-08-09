package com.qducks.superducksystem.integration.placeholder;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

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
        return switch (params.toLowerCase()) {
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
}
