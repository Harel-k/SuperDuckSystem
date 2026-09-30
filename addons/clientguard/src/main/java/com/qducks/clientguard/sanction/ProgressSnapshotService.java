package com.qducks.clientguard.sanction;

import com.qducks.clientguard.SuperDuckClientGuard;
import com.qducks.clientguard.detect.HackDefinition;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class ProgressSnapshotService {
    private static final List<QuerySpec> SDS_QUERIES = List.of(
            new QuerySpec("balances", "SELECT * FROM balances WHERE uuid=?"),
            new QuerySpec("player_settings", "SELECT * FROM player_settings WHERE uuid=?"),
            new QuerySpec("auctions_owned", "SELECT * FROM auctions WHERE seller_uuid=?"),
            new QuerySpec("auction_claims", "SELECT * FROM auction_claims WHERE player_uuid=?"),
            new QuerySpec("orders_owned", "SELECT * FROM orders WHERE buyer_uuid=?"),
            new QuerySpec("order_fills_history", "SELECT * FROM order_fills WHERE seller_uuid=?"),
            new QuerySpec("order_claims", "SELECT * FROM order_claims WHERE player_uuid=?"),
            new QuerySpec("crate_keys", "SELECT * FROM crate_keys WHERE uuid=?"),
            new QuerySpec("key_progress", "SELECT * FROM key_progress WHERE uuid=?"),
            new QuerySpec("daily_rewards", "SELECT * FROM daily_rewards WHERE uuid=?"),
            new QuerySpec("playtime_reward_claims", "SELECT * FROM playtime_reward_claims WHERE uuid=?"),
            new QuerySpec("player_stats", "SELECT * FROM player_stats WHERE uuid=?"),
            new QuerySpec("item_recoveries", "SELECT * FROM item_recoveries WHERE player_uuid=?")
    );

    private final SuperDuckClientGuard plugin;

    public ProgressSnapshotService(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<File> create(Player player, List<HackDefinition> detections, String duckyPvpBackupYaml) {
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Snapshot capture must start on the Paper thread"));
        }

        YamlConfiguration snapshot = new YamlConfiguration();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        snapshot.set("meta.created-at", now);
        snapshot.set("meta.player-uuid", uuid.toString());
        snapshot.set("meta.player-name", player.getName());
        snapshot.set("meta.reason", "CONFIRMED_CLIENT_CHEAT");
        snapshot.set("meta.detected", detections.stream().map(HackDefinition::displayName).toList());

        snapshot.set("minecraft.inventory", encodeItems(player.getInventory().getContents()));
        snapshot.set("minecraft.ender-chest", encodeItems(player.getEnderChest().getContents()));
        snapshot.set("minecraft.level", player.getLevel());
        snapshot.set("minecraft.exp", player.getExp());
        snapshot.set("minecraft.total-exp", player.getTotalExperience());
        snapshot.set("minecraft.health", player.getHealth());
        snapshot.set("minecraft.food", player.getFoodLevel());
        snapshot.set("minecraft.saturation", player.getSaturation());
        snapshot.set("minecraft.exhaustion", player.getExhaustion());
        snapshot.set("minecraft.absorption", player.getAbsorptionAmount());
        snapshot.set("minecraft.selected-slot", player.getInventory().getHeldItemSlot());
        snapshot.set("minecraft.location.world", player.getWorld().getName());
        snapshot.set("minecraft.location.x", player.getX());
        snapshot.set("minecraft.location.y", player.getY());
        snapshot.set("minecraft.location.z", player.getZ());

        // Homes remain blocked until the actual homes-provider adapter exists.
        snapshot.set("external.homes.captured", false);

        boolean duckyCaptured = duckyPvpBackupYaml != null;
        snapshot.set("external.duckypvp-backup.captured", duckyCaptured);
        snapshot.set("external.duckypvp-backup.present",
                duckyCaptured && !duckyPvpBackupYaml.isBlank());
        if (duckyCaptured && !duckyPvpBackupYaml.isBlank()) {
            snapshot.set("external.duckypvp-backup.yaml", duckyPvpBackupYaml);
        }

        return plugin.superDuckSystem().database().submit(connection -> {
            for (QuerySpec spec : SDS_QUERIES) {
                String table = tableFrom(spec.sql);
                if (!tableExists(connection, table)) {
                    snapshot.set("sds." + spec.key, List.of());
                    continue;
                }
                snapshot.set("sds." + spec.key, rows(connection, spec.sql, uuid));
            }
            return writeAtomic(snapshot, uuid, now);
        });
    }

    private List<String> encodeItems(ItemStack[] items) {
        List<String> encoded = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            encoded.add(item == null
                    ? ""
                    : Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        }
        return encoded;
    }

    private List<Map<String, Object>> rows(Connection connection, String sql, UUID uuid) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= meta.getColumnCount(); column++) {
                        Object value = result.getObject(column);
                        if (value instanceof byte[] bytes) {
                            value = "base64:" + Base64.getEncoder().encodeToString(bytes);
                        }
                        row.put(meta.getColumnLabel(column), value);
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private String tableFrom(String sql) {
        String marker = " FROM ";
        int start = sql.toUpperCase().indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Snapshot query has no FROM: " + sql);
        String tail = sql.substring(start + marker.length()).trim();
        int space = tail.indexOf(' ');
        return space < 0 ? tail : tail.substring(0, space);
    }

    private File writeAtomic(YamlConfiguration snapshot, UUID uuid, long now) throws Exception {
        Path directory = plugin.getDataFolder().toPath().resolve("sanction-snapshots").resolve(uuid.toString());
        Files.createDirectories(directory);
        Path destination = directory.resolve(now + ".yml");
        Path temp = Files.createTempFile(directory, "snapshot-", ".tmp");
        Files.writeString(temp, snapshot.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination.toFile();
    }

    private record QuerySpec(String key, String sql) {}
}
