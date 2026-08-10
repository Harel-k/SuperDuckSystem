package com.qducks.superducksystem.integration.vault;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.TransactionType;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
public final class SuperDuckVaultEconomy extends AbstractEconomy {
    private static final long DATABASE_TIMEOUT_SECONDS = 3L;

    private final SuperDuckSystem plugin;

    public SuperDuckVaultEconomy(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "SuperDuckSystem";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return plugin.configs().economy().getInt("currencies.money.decimals", 0);
    }

    @Override
    public String format(double amount) {
        return plugin.economy().formatter().format(CurrencyType.MONEY, BigDecimal.valueOf(amount));
    }

    @Override
    public String currencyNamePlural() {
        return plugin.configs().economy().getString("currencies.money.name", "Money");
    }

    @Override
    public String currencyNameSingular() {
        return plugin.configs().economy().getString("currencies.money.singular", "Dollar");
    }

    @Override
    public boolean hasAccount(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return player.hasPlayedBefore() || player.isOnline();
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public double getBalance(String playerName) {
        return balance(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(String playerName, double amount) {
        if (!Double.isFinite(amount) || amount < 0) return false;
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        double fallback = cachedBalance(player);
        if (!validAmount(amount)) {
            return failure(amount, fallback, "Amount must be positive and finite");
        }
        if (!plugin.database().isReady()) {
            return failure(amount, fallback, "SuperDuckSystem database is still starting");
        }
        try {
            BigDecimal updated = plugin.economy().take(player.getUniqueId(), CurrencyType.MONEY,
                    BigDecimal.valueOf(amount), TransactionType.SYSTEM, null)
                    .get(DATABASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return success(amount, updated.doubleValue());
        } catch (Exception exception) {
            // Do not call getBalance() here: if the DB is unhealthy that would block a second time.
            return failure(amount, cachedBalance(player), rootMessage(exception));
        }
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        double fallback = cachedBalance(player);
        if (!validAmount(amount)) {
            return failure(amount, fallback, "Amount must be positive and finite");
        }
        if (!plugin.database().isReady()) {
            return failure(amount, fallback, "SuperDuckSystem database is still starting");
        }
        try {
            BigDecimal updated = plugin.economy().add(player.getUniqueId(), CurrencyType.MONEY,
                    BigDecimal.valueOf(amount), TransactionType.SYSTEM, null)
                    .get(DATABASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return success(amount, updated.doubleValue());
        } catch (Exception exception) {
            return failure(amount, cachedBalance(player), rootMessage(exception));
        }
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        if (!plugin.database().isReady()) {
            return false;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        try {
            plugin.economy().balance(player.getUniqueId(), CurrencyType.MONEY)
                    .get(DATABASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    private double balance(OfflinePlayer player) {
        double cached = cachedBalance(player);
        if (!plugin.database().isReady()) {
            return cached;
        }
        try {
            // EconomyService returns an already-completed future once this account is warm, while
            // the first request can still load the actual value instead of briefly exposing $0.
            return plugin.economy().balance(player.getUniqueId(), CurrencyType.MONEY)
                    .get(DATABASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .doubleValue();
        } catch (Exception exception) {
            return cached;
        }
    }

    private double cachedBalance(OfflinePlayer player) {
        return plugin.economy().cachedBalance(player.getUniqueId(), CurrencyType.MONEY).doubleValue();
    }

    private boolean validAmount(double amount) {
        return Double.isFinite(amount) && amount > 0;
    }

    private EconomyResponse success(double amount, double balance) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse failure(double amount, double balance, String error) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, error);
    }

    private EconomyResponse notImplemented() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
