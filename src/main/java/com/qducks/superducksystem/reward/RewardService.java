package com.qducks.superducksystem.reward;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.TransactionType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RewardService {
    private final SuperDuckSystem plugin;
    private final AtomicBoolean starting = new AtomicBoolean();
    private volatile boolean ready;

    public RewardService(SuperDuckSystem plugin) {
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
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS daily_rewards (uuid TEXT PRIMARY KEY NOT NULL,last_claim INTEGER NOT NULL DEFAULT 0,streak INTEGER NOT NULL DEFAULT 0)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS playtime_reward_claims (uuid TEXT NOT NULL,reward_id TEXT NOT NULL,claimed_at INTEGER NOT NULL,PRIMARY KEY(uuid, reward_id))");
            }
            return null;
        }).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            starting.set(false);
            if (error != null) {
                plugin.getLogger().severe("Could not initialize rewards database: " + error.getMessage());
                return;
            }
            ready = true;
            plugin.getLogger().info("Rewards database ready.");
        }));
    }

    public boolean ready() { return ready; }

    public CompletableFuture<DailyStatus> dailyStatus(UUID uuid) {
        if (!ready) return CompletableFuture.failedFuture(new IllegalStateException("Rewards are starting"));
        long cooldown = Math.max(1L, plugin.configs().rewards().getLong("daily.cooldown-hours", 24L)) * 3_600_000L;
        return plugin.database().submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT last_claim,streak FROM daily_rewards WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return new DailyStatus(true, 0, 0, 0);
                    long last = result.getLong("last_claim");
                    int streak = result.getInt("streak");
                    long remaining = Math.max(0L, last + cooldown - System.currentTimeMillis());
                    return new DailyStatus(remaining <= 0, remaining, streak, last);
                }
            }
        });
    }

    public CompletableFuture<DailyClaim> claimDaily(Player player) {
        if (!plugin.configs().rewards().getBoolean("daily.enabled", true)) return CompletableFuture.failedFuture(new IllegalStateException("Daily rewards are disabled"));
        if (!ready) return CompletableFuture.failedFuture(new IllegalStateException("Rewards are starting"));
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldown = Math.max(1L, plugin.configs().rewards().getLong("daily.cooldown-hours", 24L)) * 3_600_000L;
        long reset = Math.max(1L, plugin.configs().rewards().getLong("daily.streak-reset-hours", 48L)) * 3_600_000L;

        return plugin.database().submit(connection -> {
            boolean old = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long previousClaim = 0L;
                int previousStreak = 0;
                try (PreparedStatement query = connection.prepareStatement("SELECT last_claim,streak FROM daily_rewards WHERE uuid=?")) {
                    query.setString(1, uuid.toString());
                    try (ResultSet result = query.executeQuery()) {
                        if (result.next()) {
                            previousClaim = result.getLong("last_claim");
                            previousStreak = result.getInt("streak");
                        }
                    }
                }
                long remaining = Math.max(0L, previousClaim + cooldown - now);
                if (previousClaim > 0 && remaining > 0) throw new DailyCooldownException(remaining);
                int streak = previousClaim > 0 && now - previousClaim <= reset ? previousStreak + 1 : 1;
                try (PreparedStatement update = connection.prepareStatement("INSERT INTO daily_rewards(uuid,last_claim,streak) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET last_claim=excluded.last_claim,streak=excluded.streak")) {
                    update.setString(1, uuid.toString()); update.setLong(2, now); update.setInt(3, streak); update.executeUpdate();
                }
                connection.commit();
                return new ReservedDaily(previousClaim, previousStreak, streak, now);
            } catch (Exception exception) {
                connection.rollback(); throw exception;
            } finally { connection.setAutoCommit(old); }
        }).thenCompose(reserved -> {
            List<Map<?, ?>> definitions = new ArrayList<>(plugin.configs().rewards().getMapList("daily.base-rewards"));
            definitions.addAll(plugin.configs().rewards().getMapList("daily.streak-bonuses." + reserved.streak));
            return grantAll(player, definitions).handle((descriptions, error) -> {
                if (error == null) return CompletableFuture.completedFuture(new DailyClaim(reserved.streak, descriptions));
                return rollbackDaily(uuid, reserved).thenCompose(ignored -> CompletableFuture.<DailyClaim>failedFuture(unwrap(error)));
            }).thenCompose(future -> future);
        });
    }

    public CompletableFuture<List<PlaytimeReward>> playtimeRewards(UUID uuid) {
        if (!ready) return CompletableFuture.failedFuture(new IllegalStateException("Rewards are starting"));
        return plugin.stats().snapshot(uuid).thenCompose(stats -> plugin.database().submit(connection -> {
            List<String> claimed = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT reward_id FROM playtime_reward_claims WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) { while (result.next()) claimed.add(result.getString("reward_id")); }
            }
            List<PlaytimeReward> rewards = new ArrayList<>();
            var section = plugin.configs().rewards().getConfigurationSection("playtime.milestones");
            if (section == null) return rewards;
            for (String id : section.getKeys(false)) {
                String base = "playtime.milestones." + id;
                long minutes = Math.max(1L, plugin.configs().rewards().getLong(base + ".minutes", 1L));
                rewards.add(new PlaytimeReward(id, minutes * 60L, claimed.contains(id), stats.playtimeSeconds() >= minutes * 60L));
            }
            rewards.sort(java.util.Comparator.comparingLong(PlaytimeReward::requiredSeconds));
            return rewards;
        }));
    }

    public CompletableFuture<List<String>> claimPlaytime(Player player, String rewardId) {
        UUID uuid = player.getUniqueId();
        String base = "playtime.milestones." + rewardId;
        if (!plugin.configs().rewards().isConfigurationSection(base)) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown playtime reward"));
        long required = Math.max(1L, plugin.configs().rewards().getLong(base + ".minutes", 1L)) * 60L;
        return plugin.stats().snapshot(uuid).thenCompose(stats -> {
            if (stats.playtimeSeconds() < required) return CompletableFuture.failedFuture(new NotEnoughPlaytimeException(required - stats.playtimeSeconds()));
            long now = System.currentTimeMillis();
            return plugin.database().submit(connection -> {
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO playtime_reward_claims(uuid,reward_id,claimed_at) VALUES(?,?,?)")) {
                    insert.setString(1, uuid.toString()); insert.setString(2, rewardId); insert.setLong(3, now); insert.executeUpdate();
                } catch (java.sql.SQLException exception) {
                    if (exception.getMessage() != null && exception.getMessage().toLowerCase(Locale.ROOT).contains("unique")) throw new AlreadyClaimedException();
                    throw exception;
                }
                return null;
            }).thenCompose(ignored -> grantAll(player, plugin.configs().rewards().getMapList(base + ".rewards"))
                    .handle((descriptions, error) -> error == null ? CompletableFuture.completedFuture(descriptions)
                            : deletePlaytimeClaim(uuid, rewardId).thenCompose(x -> CompletableFuture.<List<String>>failedFuture(unwrap(error))))
                    .thenCompose(future -> future));
        });
    }

    public CompletableFuture<List<String>> grantAll(Player player, List<Map<?, ?>> definitions) {
        CompletableFuture<List<String>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (Map<?, ?> definition : definitions) {
            chain = chain.thenCompose(descriptions -> grantOne(player, definition).thenApply(description -> {
                descriptions.add(description); return descriptions;
            }));
        }
        return chain.thenApply(List::copyOf);
    }

    private CompletableFuture<String> grantOne(Player player, Map<?, ?> definition) {
        String type = string(definition, "type", "").toUpperCase(Locale.ROOT);
        return switch (type) {
            case "MONEY" -> {
                BigDecimal amount = positiveDecimal(definition.get("amount"));
                yield plugin.economy().add(player.getUniqueId(), CurrencyType.MONEY, amount, TransactionType.EVENT_REWARD, null)
                        .thenApply(ignored -> plugin.economy().formatter().format(CurrencyType.MONEY, amount));
            }
            case "DUCKS" -> {
                BigDecimal amount = positiveDecimal(definition.get("amount"));
                yield plugin.economy().add(player.getUniqueId(), CurrencyType.DUCKS, amount, TransactionType.EVENT_REWARD, null)
                        .thenApply(ignored -> plugin.economy().formatter().format(CurrencyType.DUCKS, amount));
            }
            case "KEY" -> {
                String key = string(definition, "key", "").toLowerCase(Locale.ROOT);
                int amount = positiveInt(definition.get("amount"), 1);
                yield plugin.keys().giveKeys(player.getUniqueId(), key, amount).thenApply(ignored -> amount + "x " + plugin.keys().keyDisplayName(key));
            }
            case "ITEM" -> {
                Material material = Material.matchMaterial(string(definition, "material", ""));
                if (material == null || material.isAir() || !material.isItem()) yield CompletableFuture.failedFuture(new IllegalArgumentException("Invalid reward material"));
                int amount = positiveInt(definition.get("amount"), 1);
                yield giveItem(player, new ItemStack(material), amount).thenApply(ignored -> amount + "x " + pretty(material.name()));
            }
            case "CUSTOM_ITEM" -> {
                String id = string(definition, "id", string(definition, "item", ""));
                int amount = positiveInt(definition.get("amount"), 1);
                ItemStack item = plugin.customItems().createConfigured(id, Math.min(amount, 64));
                if (item == null) yield CompletableFuture.failedFuture(new IllegalArgumentException("Invalid custom reward item: " + id));
                yield giveItem(player, item, amount).thenApply(ignored -> amount + "x " + id);
            }
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported reward type: " + type));
        };
    }

    private CompletableFuture<Void> giveItem(Player player, ItemStack template, int requested) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (!player.isOnline()) throw new IllegalStateException("Player went offline");
                int left = requested;
                int max = Math.max(1, template.getMaxStackSize());
                while (left > 0) {
                    ItemStack stack = template.clone();
                    stack.setAmount(Math.min(left, max));
                    player.getInventory().addItem(stack).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                    left -= stack.getAmount();
                }
                future.complete(null);
            } catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future;
    }

    private CompletableFuture<Void> rollbackDaily(UUID uuid, ReservedDaily reserved) {
        return plugin.database().submit(connection -> {
            if (reserved.previousClaim == 0L) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM daily_rewards WHERE uuid=? AND last_claim=?")) {
                    delete.setString(1, uuid.toString()); delete.setLong(2, reserved.claimedAt); delete.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement("UPDATE daily_rewards SET last_claim=?,streak=? WHERE uuid=? AND last_claim=?")) {
                    update.setLong(1, reserved.previousClaim); update.setInt(2, reserved.previousStreak); update.setString(3, uuid.toString()); update.setLong(4, reserved.claimedAt); update.executeUpdate();
                }
            }
            return null;
        });
    }

    private CompletableFuture<Void> deletePlaytimeClaim(UUID uuid, String id) {
        return plugin.database().submit(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM playtime_reward_claims WHERE uuid=? AND reward_id=?")) {
                delete.setString(1, uuid.toString()); delete.setString(2, id); delete.executeUpdate();
            }
            return null;
        });
    }

    private String string(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private BigDecimal positiveDecimal(Object value) {
        BigDecimal amount = new BigDecimal(String.valueOf(value));
        if (amount.signum() <= 0) throw new IllegalArgumentException("Reward amount must be positive");
        return amount;
    }

    private int positiveInt(Object value, int fallback) {
        int amount;
        try { amount = Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { amount = fallback; }
        if (amount <= 0) throw new IllegalArgumentException("Reward amount must be positive");
        return amount;
    }

    private String pretty(String raw) {
        String[] words = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    public static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(Math.max(0L, millis));
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        long seconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds();
        if (hours > 0) return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
        return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
    }

    public record DailyStatus(boolean available, long remainingMillis, int streak, long lastClaim) { }
    public record DailyClaim(int streak, List<String> rewards) { }
    public record PlaytimeReward(String id, long requiredSeconds, boolean claimed, boolean unlocked) { }
    private record ReservedDaily(long previousClaim, int previousStreak, int streak, long claimedAt) { }

    public static final class DailyCooldownException extends RuntimeException {
        private final long remainingMillis;
        public DailyCooldownException(long remainingMillis) { super("Daily reward is on cooldown"); this.remainingMillis = remainingMillis; }
        public long remainingMillis() { return remainingMillis; }
    }
    public static final class AlreadyClaimedException extends RuntimeException { }
    public static final class NotEnoughPlaytimeException extends RuntimeException {
        private final long remainingSeconds;
        public NotEnoughPlaytimeException(long remainingSeconds) { this.remainingSeconds = remainingSeconds; }
        public long remainingSeconds() { return remainingSeconds; }
    }
}
