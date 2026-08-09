package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class KeyService implements Listener {
    private final SuperDuckSystem plugin;
    private final Map<UUID, SessionProgress> sessions = new ConcurrentHashMap<>();
    private final Map<KeyAccount, Integer> balances = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean schemaStarting = new AtomicBoolean(false);
    private volatile boolean schemaReady;
    private BukkitTask ticker;

    public KeyService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        sessions.clear();
        balances.clear();
        loading.clear();
        schemaReady = false;
    }

    public boolean ready() {
        return schemaReady;
    }

    public int cachedKeys(UUID uuid, String keyId) {
        return balances.getOrDefault(new KeyAccount(uuid, normalizeKey(keyId)), 0);
    }

    public List<String> configuredKeyIds() {
        FileConfiguration config = plugin.configs().crates();
        if (!config.isConfigurationSection("keys")) {
            return List.of();
        }
        return new ArrayList<>(config.getConfigurationSection("keys").getKeys(false));
    }

    public ProgressSnapshot snapshot(UUID uuid) {
        SessionProgress session = sessions.get(uuid);
        List<Milestone> milestones = milestones();
        if (session == null) {
            return new ProgressSnapshot(false, "", 0L, 0L, false, 0, milestones.size());
        }

        Milestone current;
        boolean repeat;
        if (session.milestoneIndex >= milestones.size()) {
            current = repeatMilestone();
            repeat = true;
        } else {
            current = milestones.get(session.milestoneIndex);
            repeat = false;
        }

        long elapsed = Math.max(0L, (System.currentTimeMillis() - session.startedAt) / 1000L);
        long remaining = Math.max(0L, current.seconds - elapsed);
        return new ProgressSnapshot(
                true,
                current.keyId,
                remaining,
                current.seconds,
                repeat,
                session.milestoneIndex,
                milestones.size()
        );
    }

    public CompletableFuture<Integer> giveKeys(UUID uuid, String requestedKeyId, int amount) {
        String keyId = normalizeKey(requestedKeyId);
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Key amount must be positive"));
        }
        if (!configuredKeyIds().stream().map(this::normalizeKey).toList().contains(keyId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown key: " + keyId));
        }
        return plugin.database().submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO crate_keys(uuid, key_id, amount) VALUES(?, ?, ?) " +
                            "ON CONFLICT(uuid, key_id) DO UPDATE SET amount=amount+excluded.amount")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, keyId);
                statement.setInt(3, amount);
                statement.executeUpdate();
            }
            int updated = readBalance(connection, uuid, keyId);
            balances.put(new KeyAccount(uuid, keyId), updated);
            return updated;
        });
    }

    public CompletableFuture<Integer> consumeKey(UUID uuid, String requestedKeyId) {
        String keyId = normalizeKey(requestedKeyId);
        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int current = readBalance(connection, uuid, keyId);
                if (current <= 0) {
                    throw new NoKeyException(keyId);
                }
                int updated = current - 1;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO crate_keys(uuid, key_id, amount) VALUES(?, ?, ?) " +
                                "ON CONFLICT(uuid, key_id) DO UPDATE SET amount=excluded.amount")) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, keyId);
                    statement.setInt(3, updated);
                    statement.executeUpdate();
                }
                connection.commit();
                balances.put(new KeyAccount(uuid, keyId), updated);
                return updated;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (schemaReady) {
            loadPlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Partial time is deliberately not saved. The player restarts the current milestone next join.
        sessions.remove(uuid);
        loading.remove(uuid);
        balances.keySet().removeIf(account -> account.uuid.equals(uuid));
    }

    private void tick() {
        if (!schemaReady) {
            tryStartSchema();
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!sessions.containsKey(player.getUniqueId()) && !loading.contains(player.getUniqueId())) {
                loadPlayer(player);
            }
        }

        List<Milestone> milestones = milestones();
        for (Map.Entry<UUID, SessionProgress> entry : sessions.entrySet()) {
            UUID uuid = entry.getKey();
            SessionProgress session = entry.getValue();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || session.granting) {
                continue;
            }

            Milestone current = session.milestoneIndex >= milestones.size()
                    ? repeatMilestone()
                    : milestones.get(session.milestoneIndex);
            long elapsed = Math.max(0L, (System.currentTimeMillis() - session.startedAt) / 1000L);
            if (elapsed < current.seconds) {
                continue;
            }

            session.granting = true;
            int nextIndex = session.milestoneIndex >= milestones.size()
                    ? milestones.size()
                    : session.milestoneIndex + 1;
            completeMilestone(uuid, current.keyId, nextIndex).whenComplete((newBalance, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        SessionProgress currentSession = sessions.get(uuid);
                        if (currentSession == null) {
                            return;
                        }
                        if (error != null) {
                            currentSession.granting = false;
                            plugin.getLogger().warning("Could not grant playtime key to " + uuid + ": " + error.getMessage());
                            return;
                        }

                        currentSession.milestoneIndex = nextIndex;
                        currentSession.startedAt = System.currentTimeMillis();
                        currentSession.granting = false;
                        balances.put(new KeyAccount(uuid, current.keyId), newBalance);

                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null && online.isOnline()) {
                            String display = keyDisplayName(current.keyId);
                            online.sendRichMessage("<gold><bold>Key Reward!</bold></gold> <yellow>You earned a " + escape(display) + "!</yellow>");
                        }
                    })
            );
        }
    }

    private void tryStartSchema() {
        if (!plugin.database().isReady() || !schemaStarting.compareAndSet(false, true)) {
            return;
        }
        plugin.database().submit(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS crate_keys ("
                        + "uuid TEXT NOT NULL,"
                        + "key_id TEXT NOT NULL,"
                        + "amount INTEGER NOT NULL DEFAULT 0,"
                        + "PRIMARY KEY(uuid, key_id)"
                        + ")");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS key_progress ("
                        + "uuid TEXT PRIMARY KEY NOT NULL,"
                        + "milestone_index INTEGER NOT NULL DEFAULT 0"
                        + ")");
            }
            return null;
        }).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            schemaStarting.set(false);
            if (error != null) {
                plugin.getLogger().severe("Could not initialize crate/key tables: " + error.getMessage());
                return;
            }
            schemaReady = true;
            for (Player player : Bukkit.getOnlinePlayers()) {
                loadPlayer(player);
            }
            plugin.getLogger().info("Digital key database ready.");
        }));
    }

    private void loadPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (!loading.add(uuid)) {
            return;
        }
        plugin.database().submit(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO key_progress(uuid, milestone_index) VALUES(?, 0)")) {
                insert.setString(1, uuid.toString());
                insert.executeUpdate();
            }

            int milestoneIndex = 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT milestone_index FROM key_progress WHERE uuid=?")) {
                query.setString(1, uuid.toString());
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        milestoneIndex = Math.max(0, result.getInt("milestone_index"));
                    }
                }
            }

            Map<String, Integer> loadedBalances = new HashMap<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT key_id, amount FROM crate_keys WHERE uuid=?")) {
                query.setString(1, uuid.toString());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        loadedBalances.put(normalizeKey(result.getString("key_id")), Math.max(0, result.getInt("amount")));
                    }
                }
            }
            return new LoadedPlayer(milestoneIndex, loadedBalances);
        }).whenComplete((loaded, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            loading.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().warning("Could not load key progress for " + online.getName() + ": " + error.getMessage());
                return;
            }
            balances.keySet().removeIf(account -> account.uuid.equals(uuid));
            loaded.balances.forEach((keyId, amount) -> balances.put(new KeyAccount(uuid, keyId), amount));
            sessions.put(uuid, new SessionProgress(loaded.milestoneIndex, System.currentTimeMillis()));
        }));
    }

    private CompletableFuture<Integer> completeMilestone(UUID uuid, String requestedKeyId, int nextIndex) {
        String keyId = normalizeKey(requestedKeyId);
        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement key = connection.prepareStatement(
                        "INSERT INTO crate_keys(uuid, key_id, amount) VALUES(?, ?, 1) " +
                                "ON CONFLICT(uuid, key_id) DO UPDATE SET amount=amount+1")) {
                    key.setString(1, uuid.toString());
                    key.setString(2, keyId);
                    key.executeUpdate();
                }
                try (PreparedStatement progress = connection.prepareStatement(
                        "INSERT INTO key_progress(uuid, milestone_index) VALUES(?, ?) " +
                                "ON CONFLICT(uuid) DO UPDATE SET milestone_index=excluded.milestone_index")) {
                    progress.setString(1, uuid.toString());
                    progress.setInt(2, Math.max(0, nextIndex));
                    progress.executeUpdate();
                }
                int updated = readBalance(connection, uuid, keyId);
                connection.commit();
                return updated;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    private int readBalance(java.sql.Connection connection, UUID uuid, String keyId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT amount FROM crate_keys WHERE uuid=? AND key_id=?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, normalizeKey(keyId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Math.max(0, result.getInt("amount")) : 0;
            }
        }
    }

    private List<Milestone> milestones() {
        List<Milestone> result = new ArrayList<>();
        for (Map<?, ?> map : plugin.configs().crates().getMapList("playtime.milestones")) {
            Object keyRaw = map.get("key");
            Object minutesRaw = map.get("minutes");
            if (keyRaw == null || minutesRaw == null) {
                continue;
            }
            String keyId = normalizeKey(String.valueOf(keyRaw));
            long minutes;
            try {
                minutes = Long.parseLong(String.valueOf(minutesRaw));
            } catch (NumberFormatException exception) {
                continue;
            }
            if (minutes > 0) {
                result.add(new Milestone(keyId, Math.max(1L, minutes * 60L)));
            }
        }
        return result;
    }

    private Milestone repeatMilestone() {
        String keyId = normalizeKey(plugin.configs().crates().getString("playtime.repeat.key", "common"));
        long minutes = Math.max(1L, plugin.configs().crates().getLong("playtime.repeat.minutes", 30L));
        return new Milestone(keyId, minutes * 60L);
    }

    public String keyDisplayName(String keyId) {
        return plugin.configs().crates().getString("keys." + normalizeKey(keyId) + ".display-name", prettyKey(keyId));
    }

    private String prettyKey(String keyId) {
        String raw = normalizeKey(keyId).replace('_', ' ');
        if (raw.isEmpty()) {
            return "Key";
        }
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1) + " Key";
    }

    private String normalizeKey(String keyId) {
        return keyId == null ? "" : keyId.trim().toLowerCase(Locale.ROOT);
    }

    private String escape(String text) {
        return text.replace("<", "\\<");
    }

    private record KeyAccount(UUID uuid, String keyId) {
    }

    private record Milestone(String keyId, long seconds) {
    }

    private record LoadedPlayer(int milestoneIndex, Map<String, Integer> balances) {
    }

    private static final class SessionProgress {
        private int milestoneIndex;
        private long startedAt;
        private boolean granting;

        private SessionProgress(int milestoneIndex, long startedAt) {
            this.milestoneIndex = milestoneIndex;
            this.startedAt = startedAt;
        }
    }

    public record ProgressSnapshot(
            boolean loaded,
            String nextKeyId,
            long secondsRemaining,
            long durationSeconds,
            boolean repeating,
            int milestoneIndex,
            int milestoneCount
    ) {
    }

    public static final class NoKeyException extends RuntimeException {
        private final String keyId;

        public NoKeyException(String keyId) {
            super("No " + keyId + " key available");
            this.keyId = keyId;
        }

        public String keyId() {
            return keyId;
        }
    }
}
