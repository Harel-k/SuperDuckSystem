package com.qducks.superducksystem.recovery;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent fallback inbox for items that must be returned or delivered after an asynchronous
 * operation when the player is no longer online. This prevents normal disconnect timing from
 * turning a failed shop/market operation into permanent item loss.
 */
public final class ItemRecoveryService implements Listener {
    private static final long STALE_RESERVATION_MILLIS = 5L * 60L * 1000L;

    private final SuperDuckSystem plugin;
    private final AtomicBoolean schemaStarting = new AtomicBoolean();
    private final Set<UUID> deliveringPlayers = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;
    private BukkitTask startupTask;

    public ItemRecoveryService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void start() {
        startupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (ready) {
                if (startupTask != null) {
                    startupTask.cancel();
                    startupTask = null;
                }
                return;
            }
            tryStartSchema();
        }, 1L, 20L);
    }

    public void stop() {
        if (startupTask != null) {
            startupTask.cancel();
            startupTask = null;
        }
        deliveringPlayers.clear();
        ready = false;
    }

    public boolean ready() {
        return ready;
    }

    public CompletableFuture<UUID> queue(UUID playerUuid, ItemStack requestedItem, String source) {
        if (requestedItem == null || requestedItem.getType().isAir() || requestedItem.getAmount() <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Recovery item cannot be empty"));
        }
        ItemStack item = requestedItem.clone();
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String safeSource = source == null || source.isBlank() ? "UNKNOWN" : source.trim();

        return plugin.database().submit(connection -> {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO item_recoveries(id, player_uuid, item_data, source, status, created_at) "
                            + "VALUES(?, ?, ?, ?, 'PENDING', ?)")) {
                statement.setString(1, id.toString());
                statement.setString(2, playerUuid.toString());
                statement.setBytes(3, item.serializeAsBytes());
                statement.setString(4, safeSource);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
            return id;
        });
    }

    public CompletableFuture<List<UUID>> queueAll(UUID playerUuid, List<ItemStack> items, String source) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<CompletableFuture<UUID>> futures = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                futures.add(queue(playerUuid, item, source));
            }
        }
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    public CompletableFuture<List<UUID>> queueMaterial(UUID playerUuid, Material material, int amount, String source) {
        if (material == null || !material.isItem() || material.isAir() || amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Recovery material/amount is invalid"));
        }
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = amount;
        int maxStack = Math.max(1, material.getMaxStackSize());
        while (remaining > 0) {
            int size = Math.min(maxStack, remaining);
            stacks.add(new ItemStack(material, size));
            remaining -= size;
        }
        return queueAll(playerUuid, stacks, source);
    }

    public void deliverPending(Player player) {
        if (!ready || player == null || !player.isOnline() || !deliveringPlayers.add(player.getUniqueId())) {
            return;
        }
        reservePending(player.getUniqueId()).whenComplete((recoveries, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        deliveringPlayers.remove(player.getUniqueId());
                        plugin.getLogger().warning("Could not load item recoveries for " + player.getUniqueId()
                                + ": " + rootMessage(error));
                        return;
                    }
                    if (recoveries.isEmpty()) {
                        deliveringPlayers.remove(player.getUniqueId());
                        return;
                    }

                    int delivered = 0;
                    for (int index = 0; index < recoveries.size(); index++) {
                        Recovery recovery = recoveries.get(index);
                        if (!player.isOnline()) {
                            for (int remaining = index; remaining < recoveries.size(); remaining++) {
                                release(recoveries.get(remaining));
                            }
                            break;
                        }
                        try {
                            give(player, recovery.item());
                            delivered++;
                            markDelivered(recovery).exceptionally(markError -> {
                                plugin.getLogger().severe("Delivered recovery " + recovery.id()
                                        + " but could not mark it delivered: " + rootMessage(markError));
                                return null;
                            });
                        } catch (RuntimeException deliveryError) {
                            plugin.getLogger().severe("Could not deliver recovery " + recovery.id()
                                    + ": " + deliveryError.getMessage());
                            release(recovery);
                        }
                    }

                    deliveringPlayers.remove(player.getUniqueId());
                    if (delivered > 0 && player.isOnline()) {
                        player.sendRichMessage("<gold><bold>Recovered Items</bold></gold> <yellow>Returned "
                                + delivered + " saved item stack" + (delivered == 1 ? "" : "s") + " to you.</yellow>");
                    }
                })
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> deliverPending(event.getPlayer()), 20L);
    }

    private void tryStartSchema() {
        if (!plugin.database().isReady() || !schemaStarting.compareAndSet(false, true)) {
            return;
        }
        plugin.database().submit(connection -> {
            ensureSchema(connection);
            long staleBefore = System.currentTimeMillis() - STALE_RESERVATION_MILLIS;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE item_recoveries SET status='PENDING', reserved_at=NULL "
                            + "WHERE status='DELIVERING' AND (reserved_at IS NULL OR reserved_at<?)")) {
                statement.setLong(1, staleBefore);
                statement.executeUpdate();
            }
            return null;
        }).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            schemaStarting.set(false);
            if (error != null) {
                plugin.getLogger().severe("Could not initialize item recovery storage: " + rootMessage(error));
                return;
            }
            ready = true;
            plugin.getLogger().info("Persistent item recovery inbox ready.");
            for (Player player : Bukkit.getOnlinePlayers()) {
                deliverPending(player);
            }
        }));
    }

    private CompletableFuture<List<Recovery>> reservePending(UUID playerUuid) {
        long now = System.currentTimeMillis();
        return plugin.database().submit(connection -> {
            ensureSchema(connection);
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<Recovery> candidates = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT id, item_data, source, created_at FROM item_recoveries "
                                + "WHERE player_uuid=? AND status='PENDING' ORDER BY created_at ASC LIMIT 100")) {
                    query.setString(1, playerUuid.toString());
                    try (ResultSet result = query.executeQuery()) {
                        while (result.next()) {
                            candidates.add(new Recovery(
                                    UUID.fromString(result.getString("id")),
                                    playerUuid,
                                    ItemStack.deserializeBytes(result.getBytes("item_data")),
                                    result.getString("source"),
                                    result.getLong("created_at")
                            ));
                        }
                    }
                }

                List<Recovery> reserved = new ArrayList<>();
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE item_recoveries SET status='DELIVERING', reserved_at=? "
                                + "WHERE id=? AND player_uuid=? AND status='PENDING'")) {
                    for (Recovery recovery : candidates) {
                        update.setLong(1, now);
                        update.setString(2, recovery.id().toString());
                        update.setString(3, playerUuid.toString());
                        if (update.executeUpdate() == 1) {
                            reserved.add(recovery);
                        }
                    }
                }
                connection.commit();
                return reserved;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    private CompletableFuture<Void> markDelivered(Recovery recovery) {
        return plugin.database().submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE item_recoveries SET status='DELIVERED', delivered_at=?, reserved_at=NULL "
                            + "WHERE id=? AND player_uuid=? AND status='DELIVERING'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, recovery.id().toString());
                statement.setString(3, recovery.playerUuid().toString());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Recovery was no longer reserved");
                }
            }
            return null;
        });
    }

    private void release(Recovery recovery) {
        plugin.database().submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE item_recoveries SET status='PENDING', reserved_at=NULL "
                            + "WHERE id=? AND player_uuid=? AND status='DELIVERING'")) {
                statement.setString(1, recovery.id().toString());
                statement.setString(2, recovery.playerUuid().toString());
                statement.executeUpdate();
            }
            return null;
        }).exceptionally(error -> {
            plugin.getLogger().severe("Could not release recovery " + recovery.id() + ": " + rootMessage(error));
            return null;
        });
    }

    private void ensureSchema(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS item_recoveries ("
                    + "id TEXT PRIMARY KEY NOT NULL,"
                    + "player_uuid TEXT NOT NULL,"
                    + "item_data BLOB NOT NULL,"
                    + "source TEXT NOT NULL,"
                    + "status TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "reserved_at INTEGER,"
                    + "delivered_at INTEGER"
                    + ")");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_item_recoveries_player_status "
                    + "ON item_recoveries(player_uuid, status, created_at)");
        }
    }

    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Recovery(UUID id, UUID playerUuid, ItemStack item, String source, long createdAt) {
        private Recovery {
            item = item.clone();
        }

        @Override
        public ItemStack item() {
            return item.clone();
        }
    }
}
