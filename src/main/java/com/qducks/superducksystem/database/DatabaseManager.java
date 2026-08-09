package com.qducks.superducksystem.database;

import com.qducks.superducksystem.SuperDuckSystem;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
            } catch (Exception exception) {
                plugin.getLogger().severe("Failed to initialize SQLite: " + exception.getMessage());
            }
        });
    }

    public boolean isReady() {
        return ready;
    }

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
            if (plugin.getConfig().getBoolean("database.wal", true)) {
                statement.execute("PRAGMA journal_mode=WAL");
            }
        }
        return connection;
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO schema_version(version) SELECT 3 WHERE NOT EXISTS (SELECT 1 FROM schema_version)");
            statement.executeUpdate("UPDATE schema_version SET version=3 WHERE version < 3");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS players ("
                    + "uuid TEXT PRIMARY KEY NOT NULL,"
                    + "username TEXT NOT NULL,"
                    + "first_join INTEGER NOT NULL,"
                    + "last_seen INTEGER NOT NULL"
                    + ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS balances ("
                    + "uuid TEXT NOT NULL,"
                    + "currency TEXT NOT NULL,"
                    + "amount TEXT NOT NULL,"
                    + "PRIMARY KEY(uuid, currency)"
                    + ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS transactions ("
                    + "id TEXT PRIMARY KEY NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "currency TEXT NOT NULL,"
                    + "actor_uuid TEXT,"
                    + "target_uuid TEXT,"
                    + "amount TEXT NOT NULL"
                    + ")");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transactions_actor ON transactions(actor_uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transactions_target ON transactions(target_uuid, created_at)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_settings ("
                    + "uuid TEXT NOT NULL,"
                    + "setting TEXT NOT NULL,"
                    + "value INTEGER NOT NULL,"
                    + "PRIMARY KEY(uuid, setting)"
                    + ")");
        }
    }

    public void close() {
        ready = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
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
