from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# Recovery service: preserve full ItemStack metadata while splitting large owed amounts.
patch(
    "src/main/java/com/qducks/superducksystem/recovery/ItemRecoveryService.java",
    "    public void deliverPending(Player player) {\n",
    """    public CompletableFuture<List<UUID>> queueAmount(UUID playerUuid, ItemStack template, int amount, String source) {
        if (template == null || template.getType().isAir() || amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(\"Recovery item/amount is invalid\"));
        }
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = amount;
        int maxStack = Math.max(1, template.getMaxStackSize());
        while (remaining > 0) {
            int size = Math.min(maxStack, remaining);
            ItemStack stack = template.clone();
            stack.setAmount(size);
            stacks.add(stack);
            remaining -= size;
        }
        return queueAll(playerUuid, stacks, source);
    }

    public void deliverPending(Player player) {
""",
)

# Shop: stale GUIs must not bypass emergency locks.
patch(
    "src/main/java/com/qducks/superducksystem/shop/ShopMenu.java",
    """    private void buy(Player player, String categoryId, int page, Material material, int amount, BigDecimal unitPrice) {
        if (amount <= 0) {
            return;
        }
""",
    """    private void buy(Player player, String categoryId, int page, Material material, int amount, BigDecimal unitPrice) {
        if (amount <= 0) {
            return;
        }
        if (plugin.state().readOnly() || plugin.state().maintenance(\"economy\") || plugin.state().maintenance(\"shop\")) {
            messages.send(player, \"shop.locked\", \"<red>The shop is temporarily unavailable.</red>\");
            return;
        }
""",
)

# Auction claims: if the player disconnects in the DB->main-thread handoff, move the already-consumed
# claim into the persistent recovery inbox rather than silently losing the item.
patch(
    "src/main/java/com/qducks/superducksystem/auction/AuctionMenu.java",
    """        service.claim(player.getUniqueId(), claimId).whenComplete((claim, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
""",
    """        service.claim(player.getUniqueId(), claimId).whenComplete((claim, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        if (error == null && claim != null) {
                            plugin.recoveries().queue(player.getUniqueId(), claim.item(), \"AUCTION_CLAIM\")
                                    .whenComplete((ignored, recoveryError) -> {
                                        if (recoveryError != null) {
                                            plugin.getLogger().severe(\"Could not persist offline auction claim recovery for \"
                                                    + player.getUniqueId() + \": \" + recoveryError.getMessage());
                                        }
                                    });
                        }
                        return;
                    }
                    if (error != null) {
""",
)

# Failed listing creation can happen after the GUI has removed the selected stack. If the seller
# disconnected while the DB operation was running, persist it instead of touching an offline inventory.
patch(
    "src/main/java/com/qducks/superducksystem/auction/AuctionMenu.java",
    """    private void restoreToSlotOrGive(Player player, ListingSelection selection) {
        if (!player.isOnline()) {
            return;
        }
        ItemStack current = player.getInventory().getItem(selection.inventorySlot());
""",
    """    private void restoreToSlotOrGive(Player player, ListingSelection selection) {
        if (!player.isOnline()) {
            plugin.recoveries().queue(player.getUniqueId(), selection.item(), \"AUCTION_LISTING_FAILED\")
                    .whenComplete((ignored, recoveryError) -> {
                        if (recoveryError != null) {
                            plugin.getLogger().severe(\"Could not persist failed auction listing recovery for \"
                                    + player.getUniqueId() + \": \" + recoveryError.getMessage());
                        }
                    });
            return;
        }
        ItemStack current = player.getInventory().getItem(selection.inventorySlot());
""",
)

# Auction service itself enforces maintenance, so already-open GUIs cannot continue writing.
patch(
    "src/main/java/com/qducks/superducksystem/auction/AuctionService.java",
    """    ) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
""",
    """    ) {
        if (plugin.state().maintenance(\"auctions\")) {
            return CompletableFuture.failedFuture(new IllegalStateException(\"Auctions are temporarily in maintenance mode\"));
        }
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
""",
)
patch(
    "src/main/java/com/qducks/superducksystem/auction/AuctionService.java",
    """    public CompletableFuture<PurchaseResult> purchase(UUID buyerUuid, UUID listingId) {
        long now = System.currentTimeMillis();
""",
    """    public CompletableFuture<PurchaseResult> purchase(UUID buyerUuid, UUID listingId) {
        if (plugin.state().maintenance(\"auctions\")) {
            return CompletableFuture.failedFuture(new IllegalStateException(\"Auctions are temporarily in maintenance mode\"));
        }
        long now = System.currentTimeMillis();
""",
)
patch(
    "src/main/java/com/qducks/superducksystem/auction/AuctionService.java",
    """    public CompletableFuture<AuctionListing> cancel(UUID sellerUuid, UUID listingId) {
        long now = System.currentTimeMillis();
""",
    """    public CompletableFuture<AuctionListing> cancel(UUID sellerUuid, UUID listingId) {
        if (plugin.state().maintenance(\"auctions\")) {
            return CompletableFuture.failedFuture(new IllegalStateException(\"Auctions are temporarily in maintenance mode\"));
        }
        long now = System.currentTimeMillis();
""",
)

# Order fills remove items before the async transaction. Refuse before removal when money/orders are
# locked, and use the recovery inbox if a genuine DB failure races with disconnect.
patch(
    "src/main/java/com/qducks/superducksystem/order/OrderMenu.java",
    """    private void fillOrder(Player seller, OrderListing order, int amount) {
        List<ItemStack> removed = removeMatching(seller.getInventory(), order.item(), amount);
""",
    """    private void fillOrder(Player seller, OrderListing order, int amount) {
        if (plugin.state().readOnly() || plugin.state().maintenance(\"economy\") || plugin.state().maintenance(\"orders\")) {
            messages.send(seller, \"order.locked\", \"<red>Orders are temporarily unavailable.</red>\");
            return;
        }
        List<ItemStack> removed = removeMatching(seller.getInventory(), order.item(), amount);
""",
)

patch(
    "src/main/java/com/qducks/superducksystem/order/OrderMenu.java",
    """        service.claim(player.getUniqueId(), claim.id()).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
""",
    """        service.claim(player.getUniqueId(), claim.id()).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        if (error == null && result != null) {
                            plugin.recoveries().queueAmount(player.getUniqueId(), result.item(), result.amount(), \"ORDER_CLAIM\")
                                    .whenComplete((ignored, recoveryError) -> {
                                        if (recoveryError != null) {
                                            plugin.getLogger().severe(\"Could not persist offline order claim recovery for \"
                                                    + player.getUniqueId() + \": \" + recoveryError.getMessage());
                                        }
                                    });
                        }
                        return;
                    }
                    if (error != null) {
""",
)

patch(
    "src/main/java/com/qducks/superducksystem/order/OrderMenu.java",
    """    private void restoreItems(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            return;
        }
        for (ItemStack item : items) {
""",
    """    private void restoreItems(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            plugin.recoveries().queueAll(player.getUniqueId(), items, \"ORDER_FILL_FAILED\")
                    .whenComplete((ignored, recoveryError) -> {
                        if (recoveryError != null) {
                            plugin.getLogger().severe(\"Could not persist failed order fill recovery for \"
                                    + player.getUniqueId() + \": \" + recoveryError.getMessage());
                        }
                    });
            return;
        }
        for (ItemStack item : items) {
""",
)

# Order service-level maintenance guards protect stale GUIs and any future API callers.
patch(
    "src/main/java/com/qducks/superducksystem/order/OrderService.java",
    """    ) {
        if (requestedItem == null || requestedItem.getType().isAir()) {
""",
    """    ) {
        if (plugin.state().maintenance(\"orders\")) {
            return CompletableFuture.failedFuture(new IllegalStateException(\"Orders are temporarily in maintenance mode\"));
        }
        if (requestedItem == null || requestedItem.getType().isAir()) {
""",
)
patch(
    "src/main/java/com/qducks/superducksystem/order/OrderService.java",
    """    ) {
        if (requestedAmount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(\"Fill amount must be positive\"));
        }
""",
    """    ) {
        if (plugin.state().maintenance(\"orders\")) {
            return CompletableFuture.failedFuture(new IllegalStateException(\"Orders are temporarily in maintenance mode\"));
        }
        if (requestedAmount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(\"Fill amount must be positive\"));
        }
""",
)
patch(
    "src/main/java/com/qducks/superducksystem/order/OrderService.java",
    """    public CompletableFuture<CancelResult> cancel(UUID buyerUuid, UUID orderId) {
        return plugin.database().submit(connection -> {
""",
    """    public CompletableFuture<CancelResult> cancel(UUID buyerUuid, UUID orderId) {
        if (plugin.state().maintenance(\"orders\")) {
            return CompletableFuture.failedFuture(new IllegalStateException(\"Orders are temporarily in maintenance mode\"));
        }
        return plugin.database().submit(connection -> {
""",
)

print("RC hardening patch applied successfully.")
