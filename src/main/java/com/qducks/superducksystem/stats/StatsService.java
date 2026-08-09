package com.qducks.superducksystem.stats;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StatsService {
    private final SuperDuckSystem plugin;
    private final Map<UUID, MutableStats> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionStarted = new ConcurrentHashMap<>();
    private final AtomicBoolean starting = new AtomicBoolean();
    private volatile boolean ready;

    public StatsService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.database().isReady()) {
            Bukkit.getScheduler().runTaskLater(plugin, this::start, 20L);
            return;
        }
        if (!starting.compareAndSet(false, true)) return;
        plugin.database().submit(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_stats (uuid TEXT PRIMARY KEY NOT NULL,kills INTEGER NOT NULL DEFAULT 0,deaths INTEGER NOT NULL DEFAULT 0,playtime_seconds INTEGER NOT NULL DEFAULT 0,crates_opened INTEGER NOT NULL DEFAULT 0,keys_used INTEGER NOT NULL DEFAULT 0)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_stats_kills ON player_stats(kills)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_stats_playtime ON player_stats(playtime_seconds)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_stats_crates ON player_stats(crates_opened)");
            }
            return null;
        }).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            starting.set(false);
            if (error != null) {
                plugin.getLogger().severe("Could not initialize stats tables: " + error.getMessage());
                return;
            }
            ready = true;
            for (Player player : Bukkit.getOnlinePlayers()) onJoin(player);
            plugin.getLogger().info("Player stats database ready.");
        }));
    }

    public boolean ready() { return ready; }

    public void onJoin(Player player) {
        sessionStarted.put(player.getUniqueId(), System.currentTimeMillis());
        load(player.getUniqueId());
    }

    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        long seconds = currentSessionSeconds(uuid);
        sessionStarted.remove(uuid);
        if (seconds > 0) increment(uuid, "playtime_seconds", seconds);
        cache.remove(uuid);
    }

    public CompletableFuture<Void> incrementKill(UUID uuid) { return increment(uuid, "kills", 1); }
    public CompletableFuture<Void> incrementDeath(UUID uuid) { return increment(uuid, "deaths", 1); }
    public CompletableFuture<Void> incrementCratesOpened(UUID uuid) {
        return increment(uuid, "crates_opened", 1).thenCompose(ignored -> increment(uuid, "keys_used", 1));
    }

    public Snapshot cached(UUID uuid) {
        MutableStats stats = cache.getOrDefault(uuid, new MutableStats());
        return new Snapshot(stats.kills, stats.deaths, stats.playtimeSeconds + currentSessionSeconds(uuid), stats.cratesOpened, stats.keysUsed);
    }

    public CompletableFuture<Snapshot> snapshot(UUID uuid) {
        if (!ready) return CompletableFuture.completedFuture(cached(uuid));
        return plugin.database().submit(connection -> {
            ensureRow(connection, uuid);
            try (PreparedStatement statement = connection.prepareStatement("SELECT kills,deaths,playtime_seconds,crates_opened,keys_used FROM player_stats WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return new Snapshot(0, 0, currentSessionSeconds(uuid), 0, 0);
                    MutableStats loaded = new MutableStats(result.getLong("kills"), result.getLong("deaths"), result.getLong("playtime_seconds"), result.getLong("crates_opened"), result.getLong("keys_used"));
                    cache.put(uuid, loaded);
                    return new Snapshot(loaded.kills, loaded.deaths, loaded.playtimeSeconds + currentSessionSeconds(uuid), loaded.cratesOpened, loaded.keysUsed);
                }
            }
        });
    }

    public CompletableFuture<Profile> profile(UUID uuid) {
        if (!ready) return CompletableFuture.completedFuture(Profile.empty(cached(uuid)));
        return plugin.database().submit(connection -> {
            ensureRow(connection, uuid);
            Snapshot base;
            try (PreparedStatement statement = connection.prepareStatement("SELECT kills,deaths,playtime_seconds,crates_opened,keys_used FROM player_stats WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        base = new Snapshot(result.getLong("kills"), result.getLong("deaths"), result.getLong("playtime_seconds") + currentSessionSeconds(uuid), result.getLong("crates_opened"), result.getLong("keys_used"));
                    } else base = new Snapshot(0, 0, currentSessionSeconds(uuid), 0, 0);
                }
            }

            long auctionsSold = scalarLong(connection, "SELECT COUNT(*) FROM auctions WHERE seller_uuid=? AND status='SOLD'", uuid);
            long ordersCreated = scalarLong(connection, "SELECT COUNT(*) FROM orders WHERE buyer_uuid=?", uuid);
            long ordersFilled = scalarLong(connection, "SELECT COUNT(*) FROM order_fills WHERE seller_uuid=?", uuid);
            long itemsSold = scalarLong(connection, "SELECT COALESCE(SUM(amount),0) FROM order_fills WHERE seller_uuid=?", uuid);

            BigDecimal moneyReceived = sumTransaction(connection, uuid, "MONEY", "target_uuid", "PAY", Sign.POSITIVE);
            BigDecimal moneySent = sumTransaction(connection, uuid, "MONEY", "actor_uuid", "PAY", Sign.POSITIVE);
            BigDecimal moneyEarned = sumTransaction(connection, uuid, "MONEY", "target_uuid", null, Sign.POSITIVE);
            BigDecimal debitSpent = sumTransaction(connection, uuid, "MONEY", "target_uuid", null, Sign.NEGATIVE).abs();
            BigDecimal moneySpent = debitSpent.add(moneySent);
            BigDecimal sellEarned = sumTransaction(connection, uuid, "MONEY", "target_uuid", "SHOP_SELL", Sign.POSITIVE);
            BigDecimal auctionEarned = sumTransaction(connection, uuid, "MONEY", "target_uuid", "AUCTION_SALE", Sign.POSITIVE);
            BigDecimal ducksEarned = sumTransaction(connection, uuid, "DUCKS", "target_uuid", null, Sign.POSITIVE);
            BigDecimal ducksSpent = sumTransaction(connection, uuid, "DUCKS", "target_uuid", null, Sign.NEGATIVE).abs();

            return new Profile(base, auctionsSold, ordersCreated, ordersFilled, itemsSold,
                    moneyEarned, moneySpent, moneySent, moneyReceived, sellEarned, auctionEarned, ducksEarned, ducksSpent);
        });
    }

    public CompletableFuture<List<LeaderboardEntry>> leaderboard(Leaderboard type, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        if (type == Leaderboard.MONEY || type == Leaderboard.DUCKS) {
            return plugin.economy().topBalances(type == Leaderboard.MONEY
                            ? com.qducks.superducksystem.economy.CurrencyType.MONEY
                            : com.qducks.superducksystem.economy.CurrencyType.DUCKS, limit)
                    .thenApply(entries -> entries.stream().map(entry -> new LeaderboardEntry(entry.uuid(), entry.username(), entry.amount().doubleValue())).toList());
        }
        String column = switch (type) {
            case KILLS -> "kills";
            case PLAYTIME -> "playtime_seconds";
            case CRATES -> "crates_opened";
            default -> "kills";
        };
        return plugin.database().submit(connection -> {
            List<LeaderboardEntry> entries = new ArrayList<>();
            String sql = "SELECT s.uuid,p.username,s." + column + " AS value FROM player_stats s LEFT JOIN players p ON p.uuid=s.uuid ORDER BY s." + column + " DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String username = result.getString("username");
                        UUID uuid = UUID.fromString(result.getString("uuid"));
                        entries.add(new LeaderboardEntry(uuid, username == null ? uuid.toString() : username, result.getDouble("value")));
                    }
                }
            }
            return entries;
        });
    }

    private void load(UUID uuid) {
        if (!ready) return;
        snapshot(uuid).exceptionally(error -> {
            plugin.getLogger().warning("Could not load stats for " + uuid + ": " + error.getMessage());
            return null;
        });
    }

    private CompletableFuture<Void> increment(UUID uuid, String column, long amount) {
        if (!ready || amount == 0) return CompletableFuture.completedFuture(null);
        return plugin.database().submit(connection -> {
            ensureRow(connection, uuid);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE player_stats SET " + column + "=" + column + "+? WHERE uuid=?")) {
                statement.setLong(1, amount);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        }).thenAccept(ignored -> cache.compute(uuid, (key, old) -> {
            MutableStats stats = old == null ? new MutableStats() : old;
            switch (column) {
                case "kills" -> stats.kills += amount;
                case "deaths" -> stats.deaths += amount;
                case "playtime_seconds" -> stats.playtimeSeconds += amount;
                case "crates_opened" -> stats.cratesOpened += amount;
                case "keys_used" -> stats.keysUsed += amount;
                default -> { }
            }
            return stats;
        }));
    }

    private void ensureRow(java.sql.Connection connection, UUID uuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO player_stats(uuid) VALUES(?)")) {
            statement.setString(1, uuid.toString()); statement.executeUpdate();
        }
    }

    private long scalarLong(java.sql.Connection connection, String sql, UUID uuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        }
    }

    private BigDecimal sumTransaction(java.sql.Connection connection, UUID uuid, String currency, String identityColumn, String type, Sign sign) throws Exception {
        String sql = "SELECT amount FROM transactions WHERE currency=? AND " + identityColumn + "=?" + (type == null ? "" : " AND type=?");
        BigDecimal total = BigDecimal.ZERO;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, currency);
            statement.setString(2, uuid.toString());
            if (type != null) statement.setString(3, type);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    BigDecimal amount = new BigDecimal(result.getString("amount"));
                    if (sign == Sign.POSITIVE && amount.signum() > 0) total = total.add(amount);
                    if (sign == Sign.NEGATIVE && amount.signum() < 0) total = total.add(amount);
                }
            }
        }
        return total;
    }

    private long currentSessionSeconds(UUID uuid) {
        Long started = sessionStarted.get(uuid);
        return started == null ? 0L : Math.max(0L, (System.currentTimeMillis() - started) / 1000L);
    }

    public enum Leaderboard { MONEY, DUCKS, KILLS, PLAYTIME, CRATES }
    private enum Sign { POSITIVE, NEGATIVE }

    public record Snapshot(long kills, long deaths, long playtimeSeconds, long cratesOpened, long keysUsed) {
        public double kd() { return deaths <= 0 ? kills : kills / (double) deaths; }
    }

    public record Profile(Snapshot base, long auctionsSold, long ordersCreated, long ordersFilled, long itemsSoldIntoOrders,
                          BigDecimal moneyEarned, BigDecimal moneySpent, BigDecimal moneySent, BigDecimal moneyReceived,
                          BigDecimal sellEarned, BigDecimal auctionEarned, BigDecimal ducksEarned, BigDecimal ducksSpent) {
        static Profile empty(Snapshot snapshot) {
            return new Profile(snapshot, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record LeaderboardEntry(UUID uuid, String username, double value) { }

    private static final class MutableStats {
        private long kills;
        private long deaths;
        private long playtimeSeconds;
        private long cratesOpened;
        private long keysUsed;
        private MutableStats() { }
        private MutableStats(long kills, long deaths, long playtimeSeconds, long cratesOpened, long keysUsed) {
            this.kills = kills; this.deaths = deaths; this.playtimeSeconds = playtimeSeconds; this.cratesOpened = cratesOpened; this.keysUsed = keysUsed;
        }
    }
}
