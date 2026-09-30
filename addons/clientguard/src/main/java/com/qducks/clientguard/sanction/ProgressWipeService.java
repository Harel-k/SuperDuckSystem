package com.qducks.clientguard.sanction;

import com.qducks.clientguard.SuperDuckClientGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ProgressWipeService {
    private final SuperDuckClientGuard plugin;

    public ProgressWipeService(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
    }

    public void prepare(UUID uuid) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Wipe preparation must run on the Paper thread");
        }

        // These caches/session clocks can otherwise recreate or expose pre-wipe state.
        plugin.superDuckSystem().stats().discardSessionAndCache(uuid);
        plugin.superDuckSystem().keys().discardSessionAndCache(uuid);
        plugin.superDuckSystem().economy().unload(uuid);
        plugin.superDuckSystem().settings().unload(uuid);
    }

    public CompletableFuture<Void> wipeSds(UUID uuid) {
        return plugin.superDuckSystem().database().submit(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // Identity and transaction history are intentionally retained for audit.
                upsertZeroBalance(connection, uuid, "MONEY");
                upsertZeroBalance(connection, uuid, "DUCKS");

                deleteIfPresent(connection, "player_settings", "uuid", uuid);
                deleteIfPresent(connection, "auction_claims", "player_uuid", uuid);
                deleteIfPresent(connection, "order_claims", "player_uuid", uuid);
                deleteIfPresent(connection, "crate_keys", "uuid", uuid);
                deleteIfPresent(connection, "key_progress", "uuid", uuid);
                deleteIfPresent(connection, "daily_rewards", "uuid", uuid);
                deleteIfPresent(connection, "playtime_reward_claims", "uuid", uuid);
                deleteIfPresent(connection, "player_stats", "uuid", uuid);
                deleteIfPresent(connection, "item_recoveries", "player_uuid", uuid);

                // Owned active market assets are destroyed, not returned/refunded.
                updateOwnedAuctions(connection, uuid);
                updateOwnedOrders(connection, uuid);

                connection.commit();
                return null;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public void wipeMinecraft(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Minecraft state wipe must run on the Paper thread");
        }

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.getEnderChest().clear();
        player.getInventory().setHeldItemSlot(0);

        player.setLevel(0);
        player.setExp(0.0F);
        player.setTotalExperience(0);
        player.setFoodLevel(20);
        player.setSaturation(5.0F);
        player.setExhaustion(0.0F);
        player.setAbsorptionAmount(0.0);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setHealth(player.getMaxHealth());

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    private void upsertZeroBalance(Connection connection, UUID uuid, String currency) throws Exception {
        if (!tableExists(connection, "balances")) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO balances(uuid,currency,amount) VALUES(?,?, '0') "
                        + "ON CONFLICT(uuid,currency) DO UPDATE SET amount='0'")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, currency);
            statement.executeUpdate();
        }
    }

    private void updateOwnedAuctions(Connection connection, UUID uuid) throws Exception {
        if (!tableExists(connection, "auctions")) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE auctions SET status='CANCELLED' WHERE seller_uuid=? AND status='LISTED'")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    private void updateOwnedOrders(Connection connection, UUID uuid) throws Exception {
        if (!tableExists(connection, "orders")) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE orders SET status='CANCELLED', escrow_remaining='0' "
                        + "WHERE buyer_uuid=? AND status='OPEN'")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    private void deleteIfPresent(Connection connection, String table, String identityColumn, UUID uuid) throws Exception {
        if (!tableExists(connection, table)) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + identityColumn + "=?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
