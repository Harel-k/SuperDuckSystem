package com.qducks.superducksystem.auction;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.EconomyService;
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
import java.util.concurrent.TimeUnit;

public final class AuctionService {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SuperDuckSystem plugin;

    public AuctionService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public int slotLimit(Player player) {
        int best = Math.max(0, plugin.configs().auctions().getInt("limits.default-slots", 3));
        String prefix = "superduck.auction.slots.";
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
                // Not a numeric slot permission.
            }
        }
        return best;
    }

    public CompletableFuture<AuctionListing> createListing(
            UUID sellerUuid,
            String sellerName,
            ItemStack item,
            BigDecimal requestedPrice,
            int slotLimit
    ) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Auction item cannot be empty"));
        }
        BigDecimal price;
        try {
            price = plugin.economy().formatter().normalize(CurrencyType.MONEY, requestedPrice);
            validatePrice(price);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        ItemStack escrowItem = item.clone();
        byte[] encoded = AuctionItemCodec.encode(escrowItem);
        long now = System.currentTimeMillis();
        long durationMinutes = Math.max(1L, plugin.configs().auctions().getLong("listing.duration-minutes", 1440L));
        long expiresAt = now + TimeUnit.MINUTES.toMillis(durationMinutes);
        UUID id = UUID.randomUUID();
        String searchText = searchText(escrowItem);

        return plugin.database().submit(connection -> {
            expireListings(connection, now);
            int active = activeCount(connection, sellerUuid, now);
            if (active >= Math.max(0, slotLimit)) {
                throw new SlotLimitException(active, slotLimit);
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO auctions(id, seller_uuid, seller_name, item_data, item_material, search_text, price, created_at, expires_at, status) " +
                            "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, id.toString());
                statement.setString(2, sellerUuid.toString());
                statement.setString(3, sellerName);
                statement.setBytes(4, encoded);
                statement.setString(5, escrowItem.getType().name());
                statement.setString(6, searchText);
                statement.setString(7, price.toPlainString());
                statement.setLong(8, now);
                statement.setLong(9, expiresAt);
                statement.setString(10, AuctionStatus.LISTED.name());
                statement.executeUpdate();
            }

            return new AuctionListing(
                    id, sellerUuid, sellerName, escrowItem, searchText, price,
                    now, expiresAt, AuctionStatus.LISTED, null, null
            );
        });
    }

    public CompletableFuture<AuctionPage> browse(String requestedSearch, AuctionSort sort, int requestedPage, int requestedPageSize) {
        String search = requestedSearch == null ? "" : requestedSearch.trim().toLowerCase(Locale.ROOT);
        int pageSize = Math.max(1, Math.min(requestedPageSize, 54));
        int page = Math.max(0, requestedPage);
        AuctionSort safeSort = sort == null ? AuctionSort.NEWEST : sort;
        long now = System.currentTimeMillis();

        return plugin.database().submit(connection -> {
            expireListings(connection, now);
            String filter = "status='LISTED' AND expires_at>?" + (search.isBlank() ? "" : " AND search_text LIKE ?");
            int total;
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM auctions WHERE " + filter)) {
                count.setLong(1, now);
                if (!search.isBlank()) {
                    count.setString(2, "%" + search + "%");
                }
                try (ResultSet result = count.executeQuery()) {
                    total = result.next() ? result.getInt(1) : 0;
                }
            }

            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            int safePage = Math.min(page, pages - 1);
            List<AuctionListing> listings = new ArrayList<>();
            String sql = "SELECT * FROM auctions WHERE " + filter + " ORDER BY " + safeSort.sqlOrder() + " LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                statement.setLong(parameter++, now);
                if (!search.isBlank()) {
                    statement.setString(parameter++, "%" + search + "%");
                }
                statement.setInt(parameter++, pageSize);
                statement.setInt(parameter, safePage * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        listings.add(readListing(result));
                    }
                }
            }
            return new AuctionPage(listings, safePage, pages, total, search, safeSort);
        });
    }

    public CompletableFuture<List<AuctionListing>> sellerListings(UUID sellerUuid, boolean includeFinished) {
        long now = System.currentTimeMillis();
        return plugin.database().submit(connection -> {
            expireListings(connection, now);
            List<AuctionListing> listings = new ArrayList<>();
            String sql = includeFinished
                    ? "SELECT * FROM auctions WHERE seller_uuid=? ORDER BY created_at DESC"
                    : "SELECT * FROM auctions WHERE seller_uuid=? AND status='LISTED' ORDER BY created_at DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, sellerUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        listings.add(readListing(result));
                    }
                }
            }
            return listings;
        });
    }

    public CompletableFuture<PurchaseResult> purchase(UUID buyerUuid, UUID listingId) {
        long now = System.currentTimeMillis();
        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                AuctionListing listing = findForUpdate(connection, listingId);
                if (listing == null || listing.status() != AuctionStatus.LISTED) {
                    throw new ListingUnavailableException();
                }
                if (listing.expiresAt() <= now) {
                    markStatus(connection, listingId, AuctionStatus.EXPIRED, null, null);
                    connection.commit();
                    throw new ListingUnavailableException();
                }
                if (listing.sellerUuid().equals(buyerUuid)) {
                    throw new IllegalArgumentException("You cannot buy your own auction");
                }

                EconomyService.TransferResult transfer = plugin.economy().transferWithinTransaction(
                        connection,
                        buyerUuid,
                        listing.sellerUuid(),
                        CurrencyType.MONEY,
                        listing.price(),
                        TransactionType.AUCTION_BUY
                );

                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE auctions SET status=?, buyer_uuid=?, sold_at=? WHERE id=? AND status=?")) {
                    update.setString(1, AuctionStatus.SOLD.name());
                    update.setString(2, buyerUuid.toString());
                    update.setLong(3, now);
                    update.setString(4, listingId.toString());
                    update.setString(5, AuctionStatus.LISTED.name());
                    if (update.executeUpdate() != 1) {
                        throw new ListingUnavailableException();
                    }
                }

                connection.commit();
                plugin.economy().publishTransfer(buyerUuid, listing.sellerUuid(), CurrencyType.MONEY, transfer);
                return new PurchaseResult(listing, transfer);
            } catch (Exception exception) {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    public CompletableFuture<AuctionListing> cancel(UUID sellerUuid, UUID listingId) {
        long now = System.currentTimeMillis();
        return plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                AuctionListing listing = findForUpdate(connection, listingId);
                if (listing == null || !listing.sellerUuid().equals(sellerUuid) || listing.status() != AuctionStatus.LISTED) {
                    throw new ListingUnavailableException();
                }
                AuctionStatus next = listing.expiresAt() <= now ? AuctionStatus.EXPIRED : AuctionStatus.CANCELLED;
                markStatus(connection, listingId, next, null, null);
                connection.commit();
                return new AuctionListing(
                        listing.id(), listing.sellerUuid(), listing.sellerName(), listing.item(), listing.searchText(),
                        listing.price(), listing.createdAt(), listing.expiresAt(), next, null, null
                );
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    public CompletableFuture<Void> markClaimed(UUID sellerUuid, UUID listingId, AuctionStatus expectedStatus) {
        if (expectedStatus != AuctionStatus.CANCELLED && expectedStatus != AuctionStatus.EXPIRED) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Only returned auctions can be claimed"));
        }
        return plugin.database().submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE auctions SET status=? WHERE id=? AND seller_uuid=? AND status=?")) {
                statement.setString(1, AuctionStatus.CLAIMED.name());
                statement.setString(2, listingId.toString());
                statement.setString(3, sellerUuid.toString());
                statement.setString(4, expectedStatus.name());
                if (statement.executeUpdate() != 1) {
                    throw new ListingUnavailableException();
                }
            }
            return null;
        });
    }

    private void validatePrice(BigDecimal price) {
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("Auction price must be greater than zero");
        }
        BigDecimal minimum = new BigDecimal(plugin.configs().auctions().getString("listing.minimum-price", "1"));
        BigDecimal maximum = new BigDecimal(plugin.configs().auctions().getString("listing.maximum-price", "1000000000000"));
        if (price.compareTo(minimum) < 0 || price.compareTo(maximum) > 0) {
            throw new PriceRangeException(minimum, maximum);
        }
    }

    private int activeCount(Connection connection, UUID sellerUuid, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM auctions WHERE seller_uuid=? AND status='LISTED' AND expires_at>?")) {
            statement.setString(1, sellerUuid.toString());
            statement.setLong(2, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private void expireListings(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE auctions SET status=? WHERE status=? AND expires_at<=?")) {
            statement.setString(1, AuctionStatus.EXPIRED.name());
            statement.setString(2, AuctionStatus.LISTED.name());
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }

    private AuctionListing findForUpdate(Connection connection, UUID listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM auctions WHERE id=?")) {
            statement.setString(1, listingId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readListing(result) : null;
            }
        }
    }

    private void markStatus(Connection connection, UUID id, AuctionStatus status, UUID buyerUuid, Long soldAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE auctions SET status=?, buyer_uuid=?, sold_at=? WHERE id=?")) {
            statement.setString(1, status.name());
            statement.setString(2, buyerUuid == null ? null : buyerUuid.toString());
            if (soldAt == null) {
                statement.setObject(3, null);
            } else {
                statement.setLong(3, soldAt);
            }
            statement.setString(4, id.toString());
            statement.executeUpdate();
        }
    }

    private AuctionListing readListing(ResultSet result) throws SQLException {
        String buyerRaw = result.getString("buyer_uuid");
        long soldRaw = result.getLong("sold_at");
        Long soldAt = result.wasNull() ? null : soldRaw;
        return new AuctionListing(
                UUID.fromString(result.getString("id")),
                UUID.fromString(result.getString("seller_uuid")),
                result.getString("seller_name"),
                AuctionItemCodec.decode(result.getBytes("item_data")),
                result.getString("search_text"),
                new BigDecimal(result.getString("price")),
                result.getLong("created_at"),
                result.getLong("expires_at"),
                AuctionStatus.valueOf(result.getString("status")),
                buyerRaw == null ? null : UUID.fromString(buyerRaw),
                soldAt
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

    public record AuctionPage(
            List<AuctionListing> listings,
            int page,
            int pages,
            int totalListings,
            String search,
            AuctionSort sort
    ) {
        public AuctionPage {
            listings = List.copyOf(listings);
        }
    }

    public record PurchaseResult(AuctionListing listing, EconomyService.TransferResult transfer) {
    }

    public static final class SlotLimitException extends RuntimeException {
        private final int active;
        private final int limit;

        public SlotLimitException(int active, int limit) {
            super("Auction slot limit reached");
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
            super("Auction price is outside the configured range");
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

    public static final class ListingUnavailableException extends RuntimeException {
        public ListingUnavailableException() {
            super("Auction listing is no longer available");
        }
    }
}
