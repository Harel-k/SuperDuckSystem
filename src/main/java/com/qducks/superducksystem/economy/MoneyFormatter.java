package com.qducks.superducksystem.economy;

import com.qducks.superducksystem.SuperDuckSystem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public final class MoneyFormatter {
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);
    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Q"};

    private final SuperDuckSystem plugin;

    public MoneyFormatter(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public String format(CurrencyType currency, BigDecimal amount) {
        String base = "currencies." + currency.configKey() + ".";
        String symbol = plugin.configs().economy().getString(base + "symbol", currency == CurrencyType.MONEY ? "$" : "🦆");
        String position = plugin.configs().economy().getString(base + "symbol-position", "PREFIX");
        int decimals = Math.max(0, plugin.configs().economy().getInt(base + "decimals", 0));
        boolean compact = plugin.configs().economy().getBoolean(base + "compact.enabled", true);
        BigDecimal compactStart = new BigDecimal(plugin.configs().economy().getString(base + "compact.start-at", "10000"));

        String number = compact && amount.abs().compareTo(compactStart) >= 0
                ? compact(amount, decimals)
                : normal(amount, decimals);
        return "SUFFIX".equalsIgnoreCase(position) ? number + symbol : symbol + number;
    }

    public BigDecimal normalize(CurrencyType currency, BigDecimal amount) {
        int decimals = Math.max(0, plugin.configs().economy().getInt("currencies." + currency.configKey() + ".decimals", 0));
        return amount.setScale(decimals, RoundingMode.DOWN);
    }

    private String normal(BigDecimal amount, int decimals) {
        StringBuilder pattern = new StringBuilder("#,##0");
        if (decimals > 0) {
            pattern.append('.').append("0".repeat(decimals));
        }
        return new DecimalFormat(pattern.toString()).format(amount);
    }

    private String compact(BigDecimal amount, int decimals) {
        BigDecimal value = amount;
        int suffix = 0;
        while (value.abs().compareTo(THOUSAND) >= 0 && suffix < SUFFIXES.length - 1) {
            value = value.divide(THOUSAND, Math.max(2, decimals + 1), RoundingMode.HALF_UP);
            suffix++;
        }
        int displayDecimals = value.abs().compareTo(BigDecimal.TEN) < 0 ? Math.max(1, decimals) : decimals;
        value = value.setScale(displayDecimals, RoundingMode.HALF_UP).stripTrailingZeros();
        return value.toPlainString() + SUFFIXES[suffix];
    }
}
