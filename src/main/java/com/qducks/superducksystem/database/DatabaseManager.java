package com.qducks.superducksystem.database;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DatabaseManager {
    private final SuperDuckSystem plugin;
    private final ExecutorService executor;
    private File databaseFile;
    private volatile boolean ready;
    private BukkitTask automaticBackupTask;

    public DatabaseManager(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SuperDuckSystem-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        String fileName = plugin.getConfig().getString("database.file", "data.db");
        databaseFile = new File(plugin.getDataFolder(), fileName);
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create plugin data directory");
        }

        executor.execute(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
                try (Connection connection = openConnection()) {
                    createSchema(connection);
                    ready = true;
                    plugin.getLogger().info("SQLite database ready.");
                }
                Bukkit.getScheduler().runTask(plugin, this::reloadBackupSchedule);
            } catch (Exception exception) {
                plugin.getLogger().severe("Failed to initialize SQLite: " + exception.getMessage());
            }
        });
    }

    public boolean isReady() { return ready; }
    public File databaseFile() { return databaseFile; }

    public <T> CompletableFuture<T> submit(DatabaseOperation<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            if (!ready) {
                future.completeExceptionally(new IllegalStateException("Database is not ready"));
                return;
            }
            try (Connection connection = openConnection()) {
                future.complete(operation.execute(connection));
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    public CompletableFuture<File> backup() {
        return submit(connection -> {
            File directory = backupDirectory();
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Could not create backup directory");
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            File destination = new File(directory, "SuperDuckSystem-" + timestamp + ".db");
            int suffix = 1;
            while (destination.exists()) destination = new File(directory, "SuperDuckSystem-" + timestamp + "-" + suffix++ + ".db");
            String path = destination.getAbsolutePath().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(FULL)");
                statement.execute("VACUUM INTO '" + path + "'");
            }
            pruneBackups(plugin.getConfig().getInt("database.backups.keep", 12));
            return destination;
        });
    }

    public void reloadBackupSchedule() {
        if (automaticBackupTask != null) {
            automaticBackupTask.cancel();
            automaticBackupTask = null;
        }
        if (!ready || !plugin.getConfig().getBoolean("database.backups.enabled", true)) {
            return;
        }
        long hours = Math.max(1L, Math.min(168L, plugin.getConfig().getLong("database.backups.interval-hours", 6L)));
        long periodTicks = hours * 60L * 60L * 20L;
        automaticBackupTask = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                backup().whenComplete((file, error) -> {
                    if (error != null) {
                        plugin.getLogger().severe("Automatic SuperDuck backup failed: " + rootMessage(error));
                    } else {
                        plugin.getLogger().info("Automatic SuperDuck backup created: " + file.getName());
                    }
                }), periodTicks, periodTicks);
        plugin.getLogger().info("Automatic database backups enabled every " + hours + " hour(s).");
    }

    public void upsertPlayer(UUID uuid, String username, long now) {
        submit(connection -> {
            String sql = "INSERT INTO players(uuid, username, first_join, last_seen) VALUES(?, ?, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET username=excluded.username, last_seen=excluded.last_seen";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, username);
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            return null;
        }).exceptionally(error -> {
            plugin.getLogger().severe("Failed to save player profile for " + username + ": " + error.getMessage());
            return null;
        });
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            if (plugin.getConfig().getBoolean("database.wal", true)) statement.execute("PRAGMA journal_mode=WAL");
        }
        return connection;
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO schema_version(version) SELECT 9 WHERE NOT EXISTS (SELECT 1 FROM schema_version)");
            statement.executeUpdate("UPDATE schema_version SET version=9 WHERE version < 9");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY NOT NULL,username TEXT NOT NULL,first_join INTEGER NOT NULL,last_seen INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS balances (uuid TEXT NOT NULL,currency TEXT NOT NULL,amount TEXT NOT NULL,PRIMARY KEY(uuid, currency))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (id TEXT PRIMARY KEY NOT NULL,created_at INTEGER NOT NULL,type TEXT NOT NULL,currency TEXT NOT NULL,actor_uuid TEXT,target_uuid TEXT,amount TEXT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transactions_actor ON transactions(actor_uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transactions_target ON transactions(target_uuid, created_at)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_settings (uuid TEXT NOT NULL,setting TEXT NOT NULL,value INTEGER NOT NULL,PRIMARY KEY(uuid, setting))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS auctions (id TEXT PRIMARY KEY NOT NULL,seller_uuid TEXT NOT NULL,seller_name TEXT NOT NULL,item_data BLOB NOT NULL,item_material TEXT NOT NULL,search_text TEXT NOT NULL,price TEXT NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,status TEXT NOT NULL,buyer_uuid TEXT,sold_at INTEGER)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auctions_status_created ON auctions(status, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auctions_seller_status ON auctions(seller_uuid, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auctions_expires ON auctions(status, expires_at)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS auction_claims (id TEXT PRIMARY KEY NOT NULL,listing_id TEXT NOT NULL,player_uuid TEXT NOT NULL,item_data BLOB NOT NULL,reason TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL,claimed_at INTEGER,UNIQUE(listing_id, reason))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_claims_player_status ON auction_claims(player_uuid, status, created_at)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS orders (id TEXT PRIMARY KEY NOT NULL,buyer_uuid TEXT NOT NULL,buyer_name TEXT NOT NULL,item_data BLOB NOT NULL,item_material TEXT NOT NULL,search_text TEXT NOT NULL,total_amount INTEGER NOT NULL,remaining_amount INTEGER NOT NULL,price_each TEXT NOT NULL,escrow_remaining TEXT NOT NULL,created_at INTEGER NOT NULL,status TEXT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_orders_status_created ON orders(status, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_orders_buyer_status ON orders(buyer_uuid, status)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS order_fills (id TEXT PRIMARY KEY NOT NULL,order_id TEXT NOT NULL,seller_uuid TEXT NOT NULL,seller_name TEXT NOT NULL,amount INTEGER NOT NULL,payout TEXT NOT NULL,created_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_order_fills_order ON order_fills(order_id, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_order_fills_seller ON order_fills(seller_uuid, created_at)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS order_claims (id TEXT PRIMARY KEY NOT NULL,order_id TEXT NOT NULL,player_uuid TEXT NOT NULL,item_data BLOB NOT NULL,amount INTEGER NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL,claimed_at INTEGER)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_order_claims_player_status ON order_claims(player_uuid, status, created_at)");
        }
    }

    private File backupDirectory() {
        return new File(plugin.getDataFolder(), "backups");
    }

    private void pruneBackups(int requestedKeep) {
        int keep = Math.max(1, Math.min(100, requestedKeep));
        File[] files = backupDirectory().listFiles((directory, name) -> name.startsWith("SuperDuckSystem-") && name.endsWith(".db"));
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int index = keep; index < files.length; index++) {
            if (!files[index].delete()) {
                plugin.getLogger().warning("Could not delete old SuperDuck backup " + files[index].getName());
            }
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public void close() {
        ready = false;
        if (automaticBackupTask != null) {
            automaticBackupTask.cancel();
            automaticBackupTask = null;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface DatabaseOperation<T> {
        T execute(Connection connection) throws Exception;
    }
}
