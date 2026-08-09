package com.qducks.superducksystem.economy;

import com.qducks.superducksystem.SuperDuckSystem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class EconomyService {
    private final SuperDuckSystem plugin;
    private final MoneyFormatter formatter;
    private final Map<AccountKey, BigDecimal> cache = new ConcurrentHashMap<>();

    public EconomyService(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.formatter = new MoneyFormatter(plugin);
    }

    public MoneyFormatter formatter() { return formatter; }
    public CompletableFuture<Void> warm(UUID uuid) { return CompletableFuture.allOf(balance(uuid, CurrencyType.MONEY), balance(uuid, CurrencyType.DUCKS)); }
    public BigDecimal cachedBalance(UUID uuid, CurrencyType currency) { return cache.getOrDefault(new AccountKey(uuid, currency), startingBalance(currency)); }

    public CompletableFuture<BigDecimal> balance(UUID uuid, CurrencyType currency) {
        AccountKey key = new AccountKey(uuid, currency);
        BigDecimal cached = cache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return plugin.database().submit(connection -> {
            BigDecimal value = readOrCreate(connection, uuid, currency);
            cache.put(key, value);
            return value;
        });
    }

    public CompletableFuture<BigDecimal> set(UUID uuid, CurrencyType currency, BigDecimal requested, TransactionType type, UUID actor) {
        try { ensureMutationAllowed(type); }
        catch (ReadOnlyException exception) { return CompletableFuture.failedFuture(exception); }
        BigDecimal amount = formatter.normalize(currency, requested);
        if (amount.signum() < 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Balance cannot be negative"));
        return plugin.database().submit(connection -> {
            BigDecimal before = readOrCreate(connection, uuid, currency);
            writeBalance(connection, uuid, currency, amount);
            record(connection, type, currency, actor, uuid, amount.subtract(before));
            cache.put(new AccountKey(uuid, currency), amount);
            return amount;
        });
    }

    public CompletableFuture<BigDecimal> add(UUID uuid, CurrencyType currency, BigDecimal requested, TransactionType type, UUID actor) {
        try { ensureMutationAllowed(type); }
        catch (ReadOnlyException exception) { return CompletableFuture.failedFuture(exception); }
        final BigDecimal amount;
        try { amount = requirePositive(currency, requested); }
        catch (IllegalArgumentException exception) { return CompletableFuture.failedFuture(exception); }
        return plugin.database().submit(connection -> {
            BigDecimal updated = creditWithinTransaction(connection, uuid, currency, amount, type, actor);
            publishBalance(uuid, currency, updated);
            return updated;
        });
    }

    public CompletableFuture<BigDecimal> take(UUID uuid, CurrencyType currency, BigDecimal requested, TransactionType type, UUID actor) {
        try { ensureMutationAllowed(type); }
        catch (ReadOnlyException exception) { return CompletableFuture.failedFuture(exception); }
        final BigDecimal amount;
        try { amount = requirePositive(currency, requested); }
        catch (IllegalArgumentException exception) { return CompletableFuture.failedFuture(exception); }
        return plugin.database().submit(connection -> {
            BigDecimal updated = debitWithinTransaction(connection, uuid, currency, amount, type, actor);
            publishBalance(uuid, currency, updated);
            return updated;
        });
    }

    public CompletableFuture<TransferResult> transfer(UUID from, UUID to, CurrencyType currency, BigDecimal requested) {
        try { ensureMutationAllowed(TransactionType.PAY); }
        catch (ReadOnlyException exception) { return CompletableFuture.failedFuture(exception); }
        if (from.equals(to)) return CompletableFuture.failedFuture(new IllegalArgumentException("Cannot transfer to yourself"));
        final BigDecimal amount;
        try { amount = requirePositive(currency, requested); }
        catch (IllegalArgumentException exception) { return CompletableFuture.failedFuture(exception); }
        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                TransferResult result = transferWithinTransaction(connection, from, to, currency, amount, TransactionType.PAY);
                connection.commit();
                publishTransfer(from, to, currency, result);
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    public TransferResult transferWithinTransaction(Connection connection, UUID from, UUID to, CurrencyType currency,
                                                    BigDecimal requested, TransactionType type) throws SQLException {
        ensureMutationAllowed(type);
        if (from.equals(to)) throw new IllegalArgumentException("Cannot transfer to yourself");
        BigDecimal amount = requirePositive(currency, requested);
        BigDecimal fromBalance = readOrCreate(connection, from, currency);
        BigDecimal toBalance = readOrCreate(connection, to, currency);
        if (fromBalance.compareTo(amount) < 0) throw new InsufficientFundsException(fromBalance, amount);
        BigDecimal newFrom = fromBalance.subtract(amount);
        BigDecimal newTo = toBalance.add(amount);
        writeBalance(connection, from, currency, newFrom);
        writeBalance(connection, to, currency, newTo);
        record(connection, type, currency, from, to, amount);
        return new TransferResult(newFrom, newTo, amount);
    }

    public BigDecimal debitWithinTransaction(Connection connection, UUID account, CurrencyType currency,
                                              BigDecimal requested, TransactionType type, UUID actor) throws SQLException {
        ensureMutationAllowed(type);
        BigDecimal amount = requirePositive(currency, requested);
        BigDecimal current = readOrCreate(connection, account, currency);
        if (current.compareTo(amount) < 0) throw new InsufficientFundsException(current, amount);
        BigDecimal updated = current.subtract(amount);
        writeBalance(connection, account, currency, updated);
        record(connection, type, currency, actor, account, amount.negate());
        return updated;
    }

    public BigDecimal creditWithinTransaction(Connection connection, UUID account, CurrencyType currency,
                                               BigDecimal requested, TransactionType type, UUID actor) throws SQLException {
        ensureMutationAllowed(type);
        BigDecimal amount = requirePositive(currency, requested);
        BigDecimal current = readOrCreate(connection, account, currency);
        BigDecimal updated = current.add(amount);
        writeBalance(connection, account, currency, updated);
        record(connection, type, currency, actor, account, amount);
        return updated;
    }

    public void publishTransfer(UUID from, UUID to, CurrencyType currency, TransferResult result) {
        cache.put(new AccountKey(from, currency), result.senderBalance());
        cache.put(new AccountKey(to, currency), result.receiverBalance());
    }

    public void publishBalance(UUID account, CurrencyType currency, BigDecimal balance) {
        cache.put(new AccountKey(account, currency), balance);
    }

    public CompletableFuture<List<LeaderboardEntry>> topBalances(CurrencyType currency, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return plugin.database().submit(connection -> {
            List<LeaderboardEntry> entries = new ArrayList<>();
            String sql = "SELECT b.uuid, p.username, b.amount FROM balances b LEFT JOIN players p ON p.uuid=b.uuid WHERE b.currency=? ORDER BY CAST(b.amount AS REAL) DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, currency.name());
                statement.setInt(2, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        UUID uuid = UUID.fromString(result.getString("uuid"));
                        String username = result.getString("username");
                        if (username == null) username = uuid.toString();
                        entries.add(new LeaderboardEntry(uuid, username, new BigDecimal(result.getString("amount"))));
                    }
                }
            }
            return entries;
        });
    }

    public void unload(UUID uuid) {
        cache.keySet().removeIf(key -> key.uuid().equals(uuid));
    }

    public BigDecimal startingBalance(CurrencyType currency) {
        String raw = plugin.configs().economy().getString("currencies." + currency.configKey() + ".starting-balance", "0");
        return formatter.normalize(currency, new BigDecimal(raw));
    }

    private void ensureMutationAllowed(TransactionType type) {
        boolean locked = plugin.state().readOnly() || plugin.state().maintenance("economy");
        if (!locked) return;
        if (type == TransactionType.ADMIN_GIVE || type == TransactionType.ADMIN_TAKE
                || type == TransactionType.ADMIN_SET || type == TransactionType.ADMIN_RESET) return;
        throw new ReadOnlyException();
    }

    private BigDecimal requirePositive(CurrencyType currency, BigDecimal requested) {
        BigDecimal amount = formatter.normalize(currency, requested);
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be greater than zero");
        return amount;
    }

    private BigDecimal readOrCreate(Connection connection, UUID uuid, CurrencyType currency) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO balances(uuid, currency, amount) VALUES(?, ?, ?)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, currency.name());
            insert.setString(3, startingBalance(currency).toPlainString());
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT amount FROM balances WHERE uuid=? AND currency=?")) {
            query.setString(1, uuid.toString());
            query.setString(2, currency.name());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Balance row disappeared for " + uuid);
                return new BigDecimal(result.getString("amount"));
            }
        }
    }

    private void writeBalance(Connection connection, UUID uuid, CurrencyType currency, BigDecimal amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO balances(uuid, currency, amount) VALUES(?, ?, ?) ON CONFLICT(uuid, currency) DO UPDATE SET amount=excluded.amount")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, currency.name());
            statement.setString(3, amount.toPlainString());
            statement.executeUpdate();
        }
    }

    private void record(Connection connection, TransactionType type, CurrencyType currency, UUID actor, UUID target, BigDecimal amount) throws SQLException {
        if (!plugin.configs().economy().getBoolean("transactions.keep-history", true)) return;
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO transactions(id, created_at, type, currency, actor_uuid, target_uuid, amount) VALUES(?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, type.name());
            statement.setString(4, currency.name());
            statement.setString(5, actor == null ? null : actor.toString());
            statement.setString(6, target == null ? null : target.toString());
            statement.setString(7, amount.toPlainString());
            statement.executeUpdate();
        }
    }

    private record AccountKey(UUID uuid, CurrencyType currency) {}
    public record TransferResult(BigDecimal senderBalance, BigDecimal receiverBalance, BigDecimal amount) {}
    public record LeaderboardEntry(UUID uuid, String username, BigDecimal amount) {}

    public static final class InsufficientFundsException extends RuntimeException {
        private final BigDecimal balance;
        private final BigDecimal requested;
        public InsufficientFundsException(BigDecimal balance, BigDecimal requested) {
            super("Insufficient funds");
            this.balance = balance;
            this.requested = requested;
        }
        public BigDecimal balance() { return balance; }
        public BigDecimal requested() { return requested; }
    }

    public static final class ReadOnlyException extends RuntimeException {
        public ReadOnlyException() { super("SuperDuckSystem economy is currently locked"); }
    }
}
