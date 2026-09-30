package com.qducks.superduckclientguard;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class WipeCoordinator {
    private final SuperDuckClientGuard plugin;

    WipeCoordinator(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
    }

    CompletableFuture<WipeOutcome> wipe(Player player, List<HackDefinition> detections) {
        if (!Bukkit.isPrimaryThread()) {
            return onMain(() -> wipe(player, detections));
        }

        List<String> externalCommands = plugin.getConfig()
                .getStringList("enforcement.hacked-client.destructive-wipe.external-commands");
        boolean requireExternal = plugin.getConfig()
                .getBoolean("enforcement.hacked-client.destructive-wipe.require-external-commands", true);

        if (requireExternal && externalCommands.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "External wipe adapter is required but no external wipe commands are configured"));
        }

        final File playerSnapshot;
        try {
            playerSnapshot = savePlayerSnapshot(player, detections);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        UUID uuid = player.getUniqueId();
        return plugin.superDuckSystem().database().backup()
                .thenCompose(databaseBackup -> plugin.superDuckSystem().database().submit(connection -> {
                    wipeSdsData(connection, uuid);
                    return databaseBackup;
                }))
                .thenCompose(databaseBackup -> onMain(() -> {
                    clearVanillaProgress(player);
                    plugin.superDuckSystem().economy().unload(uuid);

                    int failedExternal = 0;
                    for (String template : externalCommands) {
                        String command = template
                                .replace("{player}", player.getName())
                                .replace("{uuid}", uuid.toString());
                        if (command.startsWith("/")) command = command.substring(1);
                        boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                        if (!accepted) {
                            failedExternal++;
                            plugin.getLogger().severe("External wipe command was not accepted: " + command);
                        }
                    }

                    return CompletableFuture.completedFuture(new WipeOutcome(
                            playerSnapshot,
                            databaseBackup,
                            externalCommands.size(),
                            failedExternal
                    ));
                }));
    }

    private void wipeSdsData(Connection connection, UUID uuid) throws Exception {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            String id = uuid.toString();

            if (tableExists(connection, "balances")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE balances SET amount='0' WHERE uuid=?")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
            }

            deleteBy(connection, "crate_keys", "uuid", id);
            deleteBy(connection, "key_progress", "uuid", id);
            deleteBy(connection, "player_stats", "uuid", id);
            deleteBy(connection, "daily_rewards", "uuid", id);
            deleteBy(connection, "playtime_reward_claims", "uuid", id);
            deleteBy(connection, "player_settings", "uuid", id);
            deleteBy(connection, "item_recoveries", "player_uuid", id);
            deleteBy(connection, "auction_claims", "player_uuid", id);
            deleteBy(connection, "order_claims", "player_uuid", id);

            if (tableExists(connection, "auctions")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE auctions SET status='CANCELLED' WHERE seller_uuid=? AND status='LISTED'")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
            }

            if (tableExists(connection, "orders")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE orders SET status='CANCELLED', escrow_remaining='0' "
                                + "WHERE buyer_uuid=? AND status='OPEN'")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
            }

            connection.commit();
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void deleteBy(Connection connection, String table, String column, String value) throws SQLException {
        if (!tableExists(connection, table)) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + "=?")) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private File savePlayerSnapshot(Player player, List<HackDefinition> detections) {
        File directory = new File(plugin.getDataFolder(), "sanction-snapshots");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create sanction snapshot directory");
        }

        long now = System.currentTimeMillis();
        File target = new File(directory, player.getUniqueId() + "-" + now + ".yml");
        File temp = new File(directory, target.getName() + ".tmp");

        YamlConfiguration snapshot = new YamlConfiguration();
        snapshot.set("created-at", now);
        snapshot.set("player.uuid", player.getUniqueId().toString());
        snapshot.set("player.name", player.getName());
        snapshot.set("detections", detections.stream().map(HackDefinition::displayName).toList());

        snapshot.set("inventory.storage", Arrays.asList(player.getInventory().getStorageContents()));
        snapshot.set("inventory.armor", Arrays.asList(player.getInventory().getArmorContents()));
        snapshot.set("inventory.offhand", player.getInventory().getItemInOffHand());
        snapshot.set("ender-chest", Arrays.asList(player.getEnderChest().getContents()));

        snapshot.set("experience.level", player.getLevel());
        snapshot.set("experience.progress", player.getExp());
        snapshot.set("experience.total", player.getTotalExperience());

        try {
            snapshot.save(temp);
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create sanction player snapshot", exception);
        } finally {
            try {
                Files.deleteIfExists(temp.toPath());
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    private void clearVanillaProgress(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.getEnderChest().clear();

        player.setExp(0.0F);
        player.setLevel(0);
        player.setTotalExperience(0);

        if (plugin.getConfig().getBoolean(
                "enforcement.hacked-client.destructive-wipe.reset-advancements", true)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "advancement revoke " + player.getName() + " everything");
        }

        player.updateInventory();
    }

    private <T> CompletableFuture<T> onMain(Supplier<CompletableFuture<T>> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                supplier.get().whenComplete((value, error) -> {
                    if (error != null) result.completeExceptionally(error);
                    else result.complete(value);
                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    record WipeOutcome(
            File playerSnapshot,
            File databaseBackup,
            int externalCommands,
            int failedExternalCommands
    ) {
        boolean hasWarnings() {
            return failedExternalCommands > 0;
        }
    }
}
