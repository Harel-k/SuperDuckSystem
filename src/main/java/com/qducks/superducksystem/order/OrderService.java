package com.qducks.superducksystem.order;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.TransactionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OrderService {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SuperDuckSystem plugin;

    public OrderService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public int slotLimit(Player player) {
        int best = Math.max(0, plugin.configs().orders().getInt("limits.default-slots", 3));
        String prefix = "superduck.order.slots.";
        for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            if (!permission.getValue()) {
                continue;
            }
            String node = permission.getPermission().toLowerCase(Locale.ROOT);
            if (!node.startsWith(prefix)) {
                continue;
            }
            try {
                best = Math.max(best, Integer.parseInt(node.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // Ignore non-numeric permission suffixes.
            }
        }
        return best;
    }

    public CompletableFuture<OrderListing> createOrder(
            UUID buyerUuid,
            String buyerName,
            ItemStack requestedItem,
            int requestedAmount,
            BigDecimal requestedPriceEach,
            int slotLimit
    ) {
        if (plugin.state().maintenance("orders")) {
            return CompletableFuture.failedFuture(new IllegalStateException("Orders are temporarily in maintenance mode"));
        }
        if (requestedItem == null || requestedItem.getType().isAir()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Order item cannot be empty"));
        }

        ItemStack template = requestedItem.clone();
        template.setAmount(1);

        final int amount;
        final BigDecimal priceEach;
        final BigDecimal totalCost;
        try {
            amount = validateAmount(requestedAmount);
            priceEach = validatePrice(requestedPriceEach);
            totalCost = plugin.economy().formatter().normalize(
                    CurrencyType.MONEY,
                    priceEach.multiply(BigDecimal.valueOf(amount))
            );
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        UUID orderId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String searchText = searchText(template);
        byte[] itemData = OrderItemCodec.encode(template);

        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int active = openCount(connection, buyerUuid);
                if (active >= Math.max(0, slotLimit)) {
                    throw new SlotLimitException(active, slotLimit);
                }

                BigDecimal buyerBalance = plugin.economy().debitWithinTransaction(
                        connection,
                        buyerUuid,
                        CurrencyType.MONEY,
                        totalCost,
                        TransactionType.ORDER_CREATE,
                        buyerUuid
                );

                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO orders(id, buyer_uuid, buyer_name, item_data, item_material, search_text, total_amount, remaining_amount, price_each, escrow_remaining, created_at, status) " +
                                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    statement.setString(1, orderId.toString());
                    statement.setString(2, buyerUuid.toString());
                    statement.setString(3, buyerName);
                    statement.setBytes(4, itemData);
                    statement.setString(5, template.getType().name());
                    statement.setString(6, searchText);
                    statement.setInt(7, amount);
                    statement.setInt(8, amount);
                    statement.setString(9, priceEach.toPlainString());
                    statement.setString(10, totalCost.toPlainString());
                    statement.setLong(11, now);
                    statement.setString(12, OrderStatus.OPEN.name());
                    statement.executeUpdate();
                }

                connection.commit();
                plugin.economy().publishBalance(buyerUuid, CurrencyType.MONEY, buyerBalance);
                return new OrderListing(
                        orderId, buyerUuid, buyerName, template, searchText,
                        amount, amount, priceEach, totalCost, now, OrderStatus.OPEN
                );
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    public CompletableFuture<OrderPage> browse(String requestedSearch, OrderSort sort, int requestedPage, int requestedPageSize) {
        String search = requestedSearch == null ? "" : requestedSearch.trim().toLowerCase(Locale.ROOT);
        int pageSize = Math.max(1, Math.min(requestedPageSize, 54));
        int page = Math.max(0, requestedPage);
        OrderSort safeSort = sort == null ? OrderSort.NEWEST : sort;

        return plugin.database().submit(connection -> {
            String filter = "status='OPEN' AND remaining_amount>0" + (search.isBlank() ? "" : " AND search_text LIKE ?");
            int total;
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM orders WHERE " + filter)) {
                if (!search.isBlank()) {
                    count.setString(1, "%" + search + "%");
                }
                try (ResultSet result = count.executeQuery()) {
                    total = result.next() ? result.getInt(1) : 0;
                }
            }

            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            int safePage = Math.min(page, pages - 1);
            List<OrderListing> listings = new ArrayList<>();
            String sql = "SELECT * FROM orders WHERE " + filter + " ORDER BY " + safeSort.sqlOrder() + " LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                if (!search.isBlank()) {
                    statement.setString(parameter++, "%" + search + "%");
                }
                statement.setInt(parameter++, pageSize);
                statement.setInt(parameter, safePage * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        listings.add(readOrder(result));
                    }
                }
            }
            return new OrderPage(listings, safePage, pages, total, search, safeSort);
        });
    }

    public CompletableFuture<List<OrderListing>> buyerOrders(UUID buyerUuid) {
        return plugin.database().submit(connection -> {
            List<OrderListing> listings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM orders WHERE buyer_uuid=? ORDER BY created_at DESC")) {
                statement.setString(1, buyerUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        listings.add(readOrder(result));
                    }
                }
            }
            return listings;
        });
    }

    public CompletableFuture<OrderListing> find(UUID orderId) {
        return plugin.database().submit(connection -> findOrder(connection, orderId));
    }

    public CompletableFuture<FillResult> fill(
            UUID sellerUuid,
            String sellerName,
            UUID orderId,
            int requestedAmount
    ) {
        if (plugin.state().maintenance("orders")) {
            return CompletableFuture.failedFuture(new IllegalStateException("Orders are temporarily in maintenance mode"));
        }
        if (requestedAmount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Fill amount must be positive"));
        }
        long now = System.currentTimeMillis();
        UUID fillId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();

        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                OrderListing order = findOrder(connection, orderId);
                if (order == null || order.status() != OrderStatus.OPEN || order.remainingAmount() <= 0) {
                    throw new OrderUnavailableException();
                }
                if (order.buyerUuid().equals(sellerUuid)) {
                    throw new IllegalArgumentException("You cannot fill your own order");
                }
                if (requestedAmount > order.remainingAmount()) {
                    throw new FillAmountException(order.remainingAmount());
                }

                BigDecimal payout = plugin.economy().formatter().normalize(
                        CurrencyType.MONEY,
                        order.priceEach().multiply(BigDecimal.valueOf(requestedAmount))
                );
                if (order.escrowRemaining().compareTo(payout) < 0) {
                    throw new IllegalStateException("Order escrow is lower than requested payout");
                }

                BigDecimal sellerBalance = plugin.economy().creditWithinTransaction(
                        connection,
                        sellerUuid,
                        CurrencyType.MONEY,
                        payout,
                        TransactionType.ORDER_FILL,
                        order.buyerUuid()
                );

                int remaining = order.remainingAmount() - requestedAmount;
                BigDecimal escrowRemaining = order.escrowRemaining().subtract(payout);
                OrderStatus status = remaining == 0 ? OrderStatus.FILLED : OrderStatus.OPEN;

                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE orders SET remaining_amount=?, escrow_remaining=?, status=? WHERE id=? AND status='OPEN' AND remaining_amount=?")) {
                    update.setInt(1, remaining);
                    update.setString(2, escrowRemaining.toPlainString());
                    update.setString(3, status.name());
                    update.setString(4, orderId.toString());
                    update.setInt(5, order.remainingAmount());
                    if (update.executeUpdate() != 1) {
                        throw new OrderUnavailableException();
                    }
                }

                try (PreparedStatement fill = connection.prepareStatement(
                        "INSERT INTO order_fills(id, order_id, seller_uuid, seller_name, amount, payout, created_at) VALUES(?, ?, ?, ?, ?, ?, ?)")) {
                    fill.setString(1, fillId.toString());
                    fill.setString(2, orderId.toString());
                    fill.setString(3, sellerUuid.toString());
                    fill.setString(4, sellerName);
                    fill.setInt(5, requestedAmount);
                    fill.setString(6, payout.toPlainString());
                    fill.setLong(7, now);
                    fill.executeUpdate();
                }

                try (PreparedStatement claim = connection.prepareStatement(
                        "INSERT INTO order_claims(id, order_id, player_uuid, item_data, amount, status, created_at) VALUES(?, ?, ?, ?, ?, 'PENDING', ?)")) {
                    claim.setString(1, claimId.toString());
                    claim.setString(2, orderId.toString());
                    claim.setString(3, order.buyerUuid().toString());
                    claim.setBytes(4, OrderItemCodec.encode(order.item()));
                    claim.setInt(5, requestedAmount);
                    claim.setLong(6, now);
                    claim.executeUpdate();
                }

                connection.commit();
                plugin.economy().publishBalance(sellerUuid, CurrencyType.MONEY, sellerBalance);
                OrderListing updated = new OrderListing(
                        order.id(), order.buyerUuid(), order.buyerName(), order.item(), order.searchText(),
                        order.totalAmount(), remaining, order.priceEach(), escrowRemaining,
                        order.createdAt(), status
                );
                return new FillResult(updated, requestedAmount, payout, claimId);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    public CompletableFuture<CancelResult> cancel(UUID buyerUuid, UUID orderId) {
        if (plugin.state().maintenance("orders")) {
            return CompletableFuture.failedFuture(new IllegalStateException("Orders are temporarily in maintenance mode"));
        }
        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                OrderListing order = findOrder(connection, orderId);
                if (order == null || order.status() != OrderStatus.OPEN || !order.buyerUuid().equals(buyerUuid)) {
                    throw new OrderUnavailableException();
                }

                BigDecimal refund = order.escrowRemaining();
                BigDecimal newBalance = null;
                if (refund.signum() > 0) {
                    newBalance = plugin.economy().creditWithinTransaction(
                            connection,
                            buyerUuid,
                            CurrencyType.MONEY,
                            refund,
                            TransactionType.ORDER_REFUND,
                            buyerUuid
                    );
                }

                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE orders SET status='CANCELLED', escrow_remaining='0' WHERE id=? AND buyer_uuid=? AND status='OPEN'")) {
                    update.setString(1, orderId.toString());
                    update.setString(2, buyerUuid.toString());
                    if (update.executeUpdate() != 1) {
                        throw new OrderUnavailableException();
                    }
                }

                connection.commit();
                if (newBalance != null) {
                    plugin.economy().publishBalance(buyerUuid, CurrencyType.MONEY, newBalance);
                }
                return new CancelResult(order, refund);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    public CompletableFuture<List<OrderClaim>> pendingClaims(UUID playerUuid) {
        return plugin.database().submit(connection -> {
            List<OrderClaim> claims = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM order_claims WHERE player_uuid=? AND status='PENDING' ORDER BY created_at ASC")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        claims.add(readClaim(result));
                    }
                }
            }
            return claims;
        });
    }

    public CompletableFuture<OrderClaim> claim(UUID playerUuid, UUID claimId) {
        long now = System.currentTimeMillis();
        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                OrderClaim claim;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT * FROM order_claims WHERE id=? AND player_uuid=? AND status='PENDING'")) {
                    query.setString(1, claimId.toString());
                    query.setString(2, playerUuid.toString());
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) {
                            throw new ClaimUnavailableException();
                        }
                        claim = readClaim(result);
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE order_claims SET status='CLAIMED', claimed_at=? WHERE id=? AND player_uuid=? AND status='PENDING'")) {
                    update.setLong(1, now);
                    update.setString(2, claimId.toString());
                    update.setString(3, playerUuid.toString());
                    if (update.executeUpdate() != 1) {
                        throw new ClaimUnavailableException();
                    }
                }
                connection.commit();
                return claim;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    public boolean matches(ItemStack stack, ItemStack template) {
        return stack != null && !stack.getType().isAir() && stack.isSimilar(template);
    }

    private int validateAmount(int amount) {
        int max = Math.max(1, plugin.configs().orders().getInt("creation.maximum-amount", 1000000));
        if (amount <= 0 || amount > max) {
            throw new AmountRangeException(max);
        }
        return amount;
    }

    private BigDecimal validatePrice(BigDecimal requested) {
        BigDecimal price = plugin.economy().formatter().normalize(CurrencyType.MONEY, requested);
        BigDecimal minimum = new BigDecimal(plugin.configs().orders().getString("creation.minimum-price-each", "1"));
        BigDecimal maximum = new BigDecimal(plugin.configs().orders().getString("creation.maximum-price-each", "1000000000000"));
        if (price.signum() <= 0 || price.compareTo(minimum) < 0 || price.compareTo(maximum) > 0) {
            throw new PriceRangeException(minimum, maximum);
        }
        return price;
    }

    private int openCount(Connection connection, UUID buyerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM orders WHERE buyer_uuid=? AND status='OPEN'")) {
            statement.setString(1, buyerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private OrderListing findOrder(Connection connection, UUID orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM orders WHERE id=?")) {
            statement.setString(1, orderId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readOrder(result) : null;
            }
        }
    }

    private OrderListing readOrder(ResultSet result) throws SQLException {
        return new OrderListing(
                UUID.fromString(result.getString("id")),
                UUID.fromString(result.getString("buyer_uuid")),
                result.getString("buyer_name"),
                OrderItemCodec.decode(result.getBytes("item_data")),
                result.getString("search_text"),
                result.getInt("total_amount"),
                result.getInt("remaining_amount"),
                new BigDecimal(result.getString("price_each")),
                new BigDecimal(result.getString("escrow_remaining")),
                result.getLong("created_at"),
                OrderStatus.valueOf(result.getString("status"))
        );
    }

    private OrderClaim readClaim(ResultSet result) throws SQLException {
        return new OrderClaim(
                UUID.fromString(result.getString("id")),
                UUID.fromString(result.getString("order_id")),
                UUID.fromString(result.getString("player_uuid")),
                OrderItemCodec.decode(result.getBytes("item_data")),
                result.getInt("amount"),
                result.getLong("created_at")
        );
    }

    private String searchText(ItemStack item) {
        StringBuilder text = new StringBuilder(item.getType().name().replace('_', ' '));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                String plain = PLAIN.serialize(displayName).trim();
                if (!plain.isEmpty()) {
                    text.append(' ').append(plain);
                }
            }
        }
        String customId = plugin.customItems().getId(item);
        if (customId != null) {
            text.append(' ').append(customId.replace('_', ' '));
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    public record OrderPage(
            List<OrderListing> listings,
            int page,
            int pages,
            int totalListings,
            String search,
            OrderSort sort
    ) {
        public OrderPage {
            listings = List.copyOf(listings);
        }
    }

    public record FillResult(OrderListing order, int amount, BigDecimal payout, UUID claimId) {
    }

    public record CancelResult(OrderListing order, BigDecimal refund) {
    }

    public static final class SlotLimitException extends RuntimeException {
        private final int active;
        private final int limit;

        public SlotLimitException(int active, int limit) {
            super("Order slot limit reached");
            this.active = active;
            this.limit = limit;
        }

        public int active() {
            return active;
        }

        public int limit() {
            return limit;
        }
    }

    public static final class PriceRangeException extends IllegalArgumentException {
        private final BigDecimal minimum;
        private final BigDecimal maximum;

        public PriceRangeException(BigDecimal minimum, BigDecimal maximum) {
            super("Order price is outside the configured range");
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public BigDecimal minimum() {
            return minimum;
        }

        public BigDecimal maximum() {
            return maximum;
        }
    }

    public static final class AmountRangeException extends IllegalArgumentException {
        private final int maximum;

        public AmountRangeException(int maximum) {
            super("Order amount is outside the configured range");
            this.maximum = maximum;
        }

        public int maximum() {
            return maximum;
        }
    }

    public static final class FillAmountException extends IllegalArgumentException {
        private final int remaining;

        public FillAmountException(int remaining) {
            super("Fill amount exceeds remaining order amount");
            this.remaining = remaining;
        }

        public int remaining() {
            return remaining;
        }
    }

    public static final class OrderUnavailableException extends RuntimeException {
        public OrderUnavailableException() {
            super("Order is no longer available");
        }
    }

    public static final class ClaimUnavailableException extends RuntimeException {
        public ClaimUnavailableException() {
            super("Order claim is no longer available");
        }
    }
}
