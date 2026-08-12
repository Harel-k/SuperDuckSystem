package com.qducks.superducksystem.auction;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.EconomyService;
import com.qducks.superducksystem.gui.DuckGui;
import com.qducks.superducksystem.gui.GuiButton;
import com.qducks.superducksystem.gui.GuiItems;
import com.qducks.superducksystem.message.MessageService;
import com.qducks.superducksystem.settings.PlayerSetting;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuctionMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35
    );

    private final SuperDuckSystem plugin;
    private final AuctionService service;
    private final MessageService messages;

    public AuctionMenu(SuperDuckSystem plugin, AuctionService service) {
        this.plugin = plugin;
        this.service = service;
        this.messages = new MessageService(plugin);
    }

    public void open(Player player, String search, AuctionSort sort, int page) {
        plugin.rankPerks().apply(player);
        FileConfiguration config = plugin.configs().auctions();
        int configuredSize = Math.max(1, config.getInt("menu.page-size", 36));
        List<Integer> contentSlots = contentSlots(config, "menu.content-slots", DEFAULT_CONTENT_SLOTS);
        int pageSize = Math.min(configuredSize, Math.max(1, contentSlots.size()));

        service.browse(search, sort, page, pageSize).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "auction.load-failed", "<red>Could not load the Auction House.</red>");
                        return;
                    }
                    renderBrowse(player, result, contentSlots);
                })
        );
    }

    /** Compatibility shortcut for /ah sell <price>. The GUI is the primary listing flow. */
    public void startListing(Player player, BigDecimal requestedPrice) {
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack held = inventory.getItem(slot);
        if (held == null || held.getType().isAir() || held.getAmount() <= 0) {
            messages.send(player, "auction.hold-item", "<red>Hold the item you want to sell in your main hand.</red>");
            return;
        }

        BigDecimal price = normalizePrice(requestedPrice);
        if (price == null) {
            messages.send(player, "errors.invalid-amount", "<red>That is not a valid amount.</red>");
            return;
        }

        ListingSelection selection = new ListingSelection(slot, held.clone());
        if (plugin.settings().get(player.getUniqueId(), PlayerSetting.AUCTION_SELL_CONFIRMATION)) {
            openSellConfirmation(player, selection, price);
        } else {
            createListing(player, selection, price);
        }
    }

    public void beginGuiListing(Player player) {
        plugin.rankPerks().apply(player);
        openInventoryPicker(player);
    }

    private void openInventoryPicker(Player player) {
        FileConfiguration config = plugin.configs().auctions();
        int rows = clampRows(config.getInt("listing.inventory-picker.rows", 6));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("listing.inventory-picker.title", "<gold><bold>Select Auction Item</bold></gold>")));
        fill(gui, config, "listing.inventory-picker.filler");
        List<Integer> displaySlots = contentSlots(config, "listing.inventory-picker.content-slots", DEFAULT_CONTENT_SLOTS);

        ItemStack[] storage = player.getInventory().getStorageContents();
        int displayIndex = 0;
        for (int inventorySlot = 0; inventorySlot < storage.length && displayIndex < displaySlots.size(); inventorySlot++) {
            ItemStack current = storage[inventorySlot];
            if (current == null || current.getType().isAir() || current.getAmount() <= 0) {
                continue;
            }
            int displaySlot = displaySlots.get(displayIndex++);
            if (!validSlot(gui, displaySlot)) {
                continue;
            }
            int selectedInventorySlot = inventorySlot;
            ItemStack selected = current.clone();
            ItemStack icon = appendLore(selected, List.of(
                    "",
                    "<gray>Amount:</gray> <white>" + selected.getAmount() + "</white>",
                    "<yellow>Click to list this stack.</yellow>"
            ));
            gui.set(displaySlot, new GuiButton(icon,
                    context -> requestListingPrice(context.player(), new ListingSelection(selectedInventorySlot, selected))));
        }

        if (displayIndex == 0) {
            int emptySlot = config.getInt("listing.inventory-picker.empty.slot", 22);
            if (validSlot(gui, emptySlot)) {
                gui.setDisplay(emptySlot, controlItem(config, "listing.inventory-picker.empty", Material.CHEST,
                        "<gray>Your inventory has no items to list.</gray>"));
            }
        }

        int backSlot = config.getInt("listing.inventory-picker.back.slot", 49);
        if (validSlot(gui, backSlot)) {
            gui.set(backSlot, new GuiButton(
                    controlItem(config, "listing.inventory-picker.back", Material.BARRIER, "<red>Back to My Auctions</red>"),
                    context -> openMyItems(context.player())
            ));
        }
        gui.open(player);
    }

    private void requestListingPrice(Player player, ListingSelection selection) {
        String defaultPrice = plugin.configs().auctions().getString("listing.default-price-input", "100");
        messages.send(player, "auction.enter-price", "<yellow>Enter the listing price on the sign.</yellow>");
        plugin.signInput().request(player, defaultPrice, input ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    BigDecimal price;
                    try {
                        price = new BigDecimal(input.replace(",", "").trim());
                    } catch (NumberFormatException exception) {
                        messages.send(player, "errors.invalid-amount", "<red>That is not a valid amount.</red>");
                        return;
                    }
                    price = normalizePrice(price);
                    if (price == null) {
                        messages.send(player, "errors.invalid-amount", "<red>That is not a valid amount.</red>");
                        return;
                    }
                    if (plugin.settings().get(player.getUniqueId(), PlayerSetting.AUCTION_SELL_CONFIRMATION)) {
                        openSellConfirmation(player, selection, price);
                    } else {
                        createListing(player, selection, price);
                    }
                })
        );
    }

    private BigDecimal normalizePrice(BigDecimal requested) {
        try {
            BigDecimal price = plugin.economy().formatter().normalize(CurrencyType.MONEY, requested);
            if (price.signum() <= 0) {
                return null;
            }
            return price;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void renderBrowse(Player player, AuctionService.AuctionPage page, List<Integer> contentSlots) {
        FileConfiguration config = plugin.configs().auctions();
        int rows = clampRows(config.getInt("menu.rows", 6));
        String title = config.getString("menu.title", "<gold><bold>Auction House</bold></gold> <gray>%page%/%pages%</gray>")
                .replace("%page%", Integer.toString(page.page() + 1))
                .replace("%pages%", Integer.toString(page.pages()));
        DuckGui gui = new DuckGui(plugin, rows, MINI.deserialize(title));
        fill(gui, config, "menu.filler");

        int index = 0;
        for (AuctionListing listing : page.listings()) {
            if (index >= contentSlots.size()) {
                break;
            }
            int slot = contentSlots.get(index++);
            if (!validSlot(gui, slot)) {
                continue;
            }
            ItemStack icon = listingIcon(listing, config.getStringList("menu.listing-lore"));
            gui.set(slot, new GuiButton(icon, context -> {
                if (listing.sellerUuid().equals(context.player().getUniqueId())) {
                    messages.send(context.player(), "auction.own-item", "<red>You cannot buy your own auction.</red>");
                    return;
                }
                boolean instant = plugin.settings().get(context.player().getUniqueId(), PlayerSetting.INSTANT_AUCTION_PURCHASE);
                boolean confirm = plugin.settings().get(context.player().getUniqueId(), PlayerSetting.AUCTION_BUY_CONFIRMATION);
                if (instant || !confirm) {
                    purchase(context.player(), listing);
                } else {
                    openBuyConfirmation(context.player(), listing, page.search(), page.sort(), page.page());
                }
            }));
        }

        int previousSlot = config.getInt("menu.previous.slot", 45);
        if (page.page() > 0 && validSlot(gui, previousSlot)) {
            ItemStack item = controlItem(config, "menu.previous", Material.ARROW, "<yellow>Previous Page</yellow>");
            gui.set(previousSlot, new GuiButton(item,
                    context -> open(context.player(), page.search(), page.sort(), page.page() - 1)));
        }

        int searchSlot = config.getInt("menu.search.slot", 46);
        if (validSlot(gui, searchSlot)) {
            String currentSearch = page.search().isBlank() ? "All Items" : page.search();
            ItemStack searchItem = controlItem(config, "menu.search", Material.OAK_SIGN, "<aqua><bold>Search</bold></aqua>",
                    Map.of("search", currentSearch));
            gui.set(searchSlot, new GuiButton(searchItem, context -> {
                Player searchPlayer = context.player();
                String initialSearch = page.search();
                plugin.signInput().request(searchPlayer, initialSearch, input ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (searchPlayer.isOnline()) {
                                open(searchPlayer, input, page.sort(), 0);
                            }
                        })
                );
            }));
        }

        int myItemsSlot = config.getInt("menu.my-items.slot", 47);
        if (validSlot(gui, myItemsSlot)) {
            ItemStack myItems = controlItem(config, "menu.my-items", Material.CHEST, "<gold><bold>My Auctions</bold></gold>");
            gui.set(myItemsSlot, new GuiButton(myItems, context -> openMyItems(context.player())));
        }

        int refreshSlot = config.getInt("menu.refresh.slot", 49);
        if (validSlot(gui, refreshSlot)) {
            ItemStack refresh = controlItem(config, "menu.refresh", Material.SUNFLOWER, "<green><bold>Refresh</bold></green>");
            gui.set(refreshSlot, new GuiButton(refresh,
                    context -> open(context.player(), page.search(), page.sort(), page.page())));
        }

        int sortSlot = config.getInt("menu.sort.slot", 51);
        if (validSlot(gui, sortSlot)) {
            ItemStack sortItem = controlItem(config, "menu.sort", Material.HOPPER, "<yellow><bold>Sort</bold></yellow>",
                    Map.of("sort", prettySort(page.sort())));
            gui.set(sortSlot, new GuiButton(sortItem,
                    context -> open(context.player(), page.search(), page.sort().next(), 0)));
        }

        int clearSearchSlot = config.getInt("menu.clear-search.slot", 52);
        if (!page.search().isBlank() && validSlot(gui, clearSearchSlot)) {
            ItemStack clear = controlItem(config, "menu.clear-search", Material.MILK_BUCKET, "<red>Clear Search</red>");
            gui.set(clearSearchSlot, new GuiButton(clear,
                    context -> open(context.player(), "", page.sort(), 0)));
        }

        int nextSlot = config.getInt("menu.next.slot", 53);
        if (page.page() + 1 < page.pages() && validSlot(gui, nextSlot)) {
            ItemStack item = controlItem(config, "menu.next", Material.ARROW, "<yellow>Next Page</yellow>");
            gui.set(nextSlot, new GuiButton(item,
                    context -> open(context.player(), page.search(), page.sort(), page.page() + 1)));
        }

        gui.open(player);
    }

    private void openBuyConfirmation(Player player, AuctionListing listing, String search, AuctionSort sort, int page) {
        FileConfiguration config = plugin.configs().auctions();
        int rows = clampRows(config.getInt("confirmation.rows", 3));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("confirmation.buy-title", "<gold><bold>Confirm Purchase</bold></gold>")));
        fill(gui, config, "confirmation.filler");

        int itemSlot = config.getInt("confirmation.item-slot", 13);
        if (validSlot(gui, itemSlot)) {
            gui.setDisplay(itemSlot, listingIcon(listing, List.of(
                    "",
                    "<gray>Price:</gray> <green>%price%</green>",
                    "<gray>Seller:</gray> <white>%seller%</white>"
            )));
        }

        int confirmSlot = config.getInt("confirmation.confirm-slot", 11);
        if (validSlot(gui, confirmSlot)) {
            Material material = material(config.getString("confirmation.confirm-material"), Material.LIME_CONCRETE);
            ItemStack confirm = GuiItems.item(material,
                    config.getString("confirmation.confirm-name", "<green><bold>Confirm</bold></green>"));
            gui.set(confirmSlot, new GuiButton(confirm, context -> purchase(context.player(), listing)));
        }

        int cancelSlot = config.getInt("confirmation.cancel-slot", 15);
        if (validSlot(gui, cancelSlot)) {
            Material material = material(config.getString("confirmation.cancel-material"), Material.RED_CONCRETE);
            ItemStack cancel = GuiItems.item(material,
                    config.getString("confirmation.cancel-name", "<red><bold>Cancel</bold></red>"));
            gui.set(cancelSlot, new GuiButton(cancel,
                    context -> open(context.player(), search, sort, page)));
        }
        gui.open(player);
    }

    private void openSellConfirmation(Player player, ListingSelection selection, BigDecimal price) {
        FileConfiguration config = plugin.configs().auctions();
        int rows = clampRows(config.getInt("confirmation.rows", 3));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("confirmation.sell-title", "<gold><bold>Confirm Listing</bold></gold>")));
        fill(gui, config, "confirmation.filler");

        int itemSlot = config.getInt("confirmation.item-slot", 13);
        if (validSlot(gui, itemSlot)) {
            ItemStack display = appendLore(selection.item().clone(), List.of(
                    "",
                    "<gray>Listing price:</gray> <green>" + plugin.economy().formatter().format(CurrencyType.MONEY, price) + "</green>",
                    "<gray>The selected inventory stack will be listed.</gray>"
            ));
            gui.setDisplay(itemSlot, display);
        }

        int confirmSlot = config.getInt("confirmation.confirm-slot", 11);
        if (validSlot(gui, confirmSlot)) {
            Material material = material(config.getString("confirmation.confirm-material"), Material.LIME_CONCRETE);
            ItemStack confirm = GuiItems.item(material,
                    config.getString("confirmation.confirm-name", "<green><bold>Confirm</bold></green>"));
            gui.set(confirmSlot, new GuiButton(confirm, context -> createListing(context.player(), selection, price)));
        }

        int cancelSlot = config.getInt("confirmation.cancel-slot", 15);
        if (validSlot(gui, cancelSlot)) {
            Material material = material(config.getString("confirmation.cancel-material"), Material.RED_CONCRETE);
            ItemStack cancel = GuiItems.item(material,
                    config.getString("confirmation.cancel-name", "<red><bold>Cancel</bold></red>"));
            gui.set(cancelSlot, new GuiButton(cancel, context -> openInventoryPicker(context.player())));
        }
        gui.open(player);
    }

    private void createListing(Player player, ListingSelection selection, BigDecimal price) {
        plugin.rankPerks().apply(player);
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(selection.inventorySlot());
        if (current == null || !current.equals(selection.item())) {
            player.closeInventory();
            messages.send(player, "auction.item-changed",
                    "<red>That inventory stack changed. Choose the item again.</red>");
            return;
        }

        int slotLimit = service.slotLimit(player);
        inventory.setItem(selection.inventorySlot(), null);
        player.closeInventory();

        service.createListing(player.getUniqueId(), player.getName(), selection.item(), price, slotLimit)
                .whenComplete((listing, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        restoreToSlotOrGive(player, selection);
                        Throwable cause = unwrap(error);
                        if (cause instanceof AuctionService.SlotLimitException limit) {
                            messages.send(player, "auction.slot-limit",
                                    "<red>You are using all %limit% of your auction slots.</red>",
                                    Map.of("limit", Integer.toString(limit.limit())));
                        } else if (cause instanceof AuctionService.PriceRangeException range) {
                            messages.send(player, "auction.price-range",
                                    "<red>Price must be between %minimum% and %maximum%.</red>", Map.of(
                                            "minimum", plugin.economy().formatter().format(CurrencyType.MONEY, range.minimum()),
                                            "maximum", plugin.economy().formatter().format(CurrencyType.MONEY, range.maximum())
                                    ));
                        } else {
                            messages.send(player, "auction.list-failed",
                                    "<red>Could not list the item, so it was returned.</red>");
                        }
                        return;
                    }
                    messages.send(player, "auction.listed",
                            "<green>Listed your item for %price%.</green>", Map.of(
                                    "price", plugin.economy().formatter().format(CurrencyType.MONEY, listing.price())
                            ));
                    openMyItems(player);
                }));
    }

    private void purchase(Player player, AuctionListing listing) {
        player.closeInventory();
        service.purchase(player.getUniqueId(), listing.id()).whenComplete((purchase, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        if (cause instanceof EconomyService.InsufficientFundsException) {
                            messages.send(player, "auction.insufficient", "<red>You do not have enough money.</red>");
                        } else if (cause instanceof AuctionService.ListingUnavailableException) {
                            messages.send(player, "auction.unavailable", "<red>That auction is no longer available.</red>");
                        } else if (cause instanceof IllegalArgumentException) {
                            messages.send(player, "auction.own-item", "<red>You cannot buy your own auction.</red>");
                        } else {
                            messages.send(player, "auction.buy-failed", "<red>The purchase failed.</red>");
                        }
                        return;
                    }

                    Player seller = Bukkit.getPlayer(purchase.listing().sellerUuid());
                    if (seller != null && plugin.settings().get(seller.getUniqueId(), PlayerSetting.AUCTION_NOTIFICATIONS)) {
                        messages.send(seller, "auction.sold-notification",
                                "<green>Your auction sold for %price%.</green>", Map.of(
                                        "price", plugin.economy().formatter().format(CurrencyType.MONEY, purchase.listing().price())
                                ));
                    }

                    if (!player.isOnline()) {
                        return;
                    }
                    claimAndDeliver(player, purchase.claimId(), true, purchase.listing().price());
                })
        );
    }

    private void openMyItems(Player player) {
        openMyItems(player, 0);
    }

    private void openMyItems(Player player, int requestedPage) {
        plugin.rankPerks().apply(player);
        var listingsFuture = service.sellerListings(player.getUniqueId(), false);
        var claimsFuture = service.pendingClaims(player.getUniqueId());
        listingsFuture.thenCombine(claimsFuture, MyItemsData::new).whenComplete((data, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "auction.load-failed", "<red>Could not load your auctions.</red>");
                        return;
                    }
                    renderMyItems(player, data, requestedPage);
                })
        );
    }

    private void renderMyItems(Player player, MyItemsData data, int requestedPage) {
        FileConfiguration config = plugin.configs().auctions();
        int rows = clampRows(config.getInt("my-items.rows", 6));
        int limit = service.slotLimit(player);
        int used = data.listings().size();
        List<Integer> slots = contentSlots(config, "my-items.content-slots", DEFAULT_CONTENT_SLOTS);
        int configuredPageSize = Math.max(1, config.getInt("my-items.page-size", slots.size()));
        int pageSize = Math.min(configuredPageSize, Math.max(1, slots.size()));
        int totalEntries = data.claims().size() + Math.max(limit, used);
        int pages = Math.max(1, (int) Math.ceil(totalEntries / (double) pageSize));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        String title = config.getString("my-items.title", "<gold><bold>My Auctions</bold></gold> <gray>%used%/%limit%</gray> <dark_gray>•</dark_gray> <gray>%page%/%pages%</gray>")
                .replace("%used%", Integer.toString(used))
                .replace("%limit%", Integer.toString(limit))
                .replace("%page%", Integer.toString(page + 1))
                .replace("%pages%", Integer.toString(pages));
        DuckGui gui = new DuckGui(plugin, rows, MINI.deserialize(title));
        fill(gui, config, "my-items.filler");

        int firstEntry = page * pageSize;
        int lastEntry = Math.min(totalEntries, firstEntry + pageSize);
        int claimCount = data.claims().size();
        int listingCount = data.listings().size();

        for (int globalIndex = firstEntry; globalIndex < lastEntry; globalIndex++) {
            int slotIndex = globalIndex - firstEntry;
            if (slotIndex >= slots.size()) break;
            int slot = slots.get(slotIndex);
            if (!validSlot(gui, slot)) continue;

            if (globalIndex < claimCount) {
                AuctionClaim claim = data.claims().get(globalIndex);
                String reason = claim.reason() == AuctionClaim.Reason.PURCHASE ? "Purchased Item" : "Returned Item";
                ItemStack icon = appendLore(claim.item(), List.of(
                        "",
                        "<green><bold>READY TO CLAIM</bold></green>",
                        "<gray>Type:</gray> <white>" + reason + "</white>",
                        "<yellow>Click to claim.</yellow>"
                ));
                gui.set(slot, new GuiButton(icon,
                        context -> claimAndDeliver(context.player(), claim.id(), false, null)));
                continue;
            }

            int marketIndex = globalIndex - claimCount;
            if (marketIndex < listingCount) {
                AuctionListing listing = data.listings().get(marketIndex);
                ItemStack icon = appendLore(listing.item(), List.of(
                        "",
                        "<gray>Status:</gray> <green>LISTED</green>",
                        "<gray>Price:</gray> <green>" + plugin.economy().formatter().format(CurrencyType.MONEY, listing.price()) + "</green>",
                        "<yellow>Click to cancel this listing.</yellow>"
                ));
                gui.set(slot, new GuiButton(icon, context -> cancelListing(context.player(), listing)));
                continue;
            }

            if (marketIndex < limit) {
                ItemStack empty = controlItem(config, "my-items.empty-slot", Material.LIME_STAINED_GLASS_PANE,
                        "<green><bold>+ List Item</bold></green>", Map.of(
                                "used", Integer.toString(used),
                                "limit", Integer.toString(limit)
                        ));
                gui.set(slot, new GuiButton(empty, context -> beginGuiListing(context.player())));
            }
        }

        int previousSlot = config.getInt("my-items.previous.slot", 48);
        if (page > 0 && validSlot(gui, previousSlot)) {
            ItemStack previous = controlItem(config, "my-items.previous", Material.ARROW, "<yellow>Previous Page</yellow>");
            gui.set(previousSlot, new GuiButton(previous,
                    context -> openMyItems(context.player(), page - 1)));
        }

        int backSlot = config.getInt("my-items.back.slot", 49);
        if (validSlot(gui, backSlot)) {
            ItemStack back = controlItem(config, "my-items.back", Material.BARRIER, "<red>Back</red>");
            gui.set(backSlot, new GuiButton(back,
                    context -> open(context.player(), "", AuctionSort.NEWEST, 0)));
        }

        int nextSlot = config.getInt("my-items.next.slot", 50);
        if (page + 1 < pages && validSlot(gui, nextSlot)) {
            ItemStack next = controlItem(config, "my-items.next", Material.ARROW, "<yellow>Next Page</yellow>");
            gui.set(nextSlot, new GuiButton(next,
                    context -> openMyItems(context.player(), page + 1)));
        }
        gui.open(player);
    }

    private void cancelListing(Player player, AuctionListing listing) {
        player.closeInventory();
        service.cancel(player.getUniqueId(), listing.id()).whenComplete((cancelled, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "auction.cancel-failed", "<red>Could not cancel that auction.</red>");
                        return;
                    }
                    messages.send(player, "auction.cancelled",
                            "<green>Auction cancelled. The item is now in My Auctions.</green>");
                    openMyItems(player);
                })
        );
    }

    private void claimAndDeliver(Player player, UUID claimId, boolean purchase, BigDecimal price) {
        if (!player.isOnline()) {
            return;
        }
        service.claim(player.getUniqueId(), claimId).whenComplete((claim, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        if (error == null && claim != null) {
                            plugin.recoveries().queue(player.getUniqueId(), claim.item(), "AUCTION_CLAIM")
                                    .whenComplete((ignored, recoveryError) -> {
                                        if (recoveryError != null) {
                                            plugin.getLogger().severe("Could not persist offline auction claim recovery for "
                                                    + player.getUniqueId() + ": " + recoveryError.getMessage());
                                        }
                                    });
                        }
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "auction.claim-failed",
                                "<yellow>Your item is still waiting in My Auctions. Open /ah and try again.</yellow>");
                        return;
                    }
                    giveItem(player, claim.item());
                    if (purchase && price != null) {
                        messages.send(player, "auction.bought",
                                "<green>Purchased the auction for %price%.</green>", Map.of(
                                        "price", plugin.economy().formatter().format(CurrencyType.MONEY, price)
                                ));
                    } else {
                        messages.send(player, "auction.claimed", "<green>Claimed your auction item.</green>");
                    }
                    openMyItems(player);
                })
        );
    }

    private void restoreToSlotOrGive(Player player, ListingSelection selection) {
        if (!player.isOnline()) {
            plugin.recoveries().queue(player.getUniqueId(), selection.item(), "AUCTION_LISTING_FAILED")
                    .whenComplete((ignored, recoveryError) -> {
                        if (recoveryError != null) {
                            plugin.getLogger().severe("Could not persist failed auction listing recovery for "
                                    + player.getUniqueId() + ": " + recoveryError.getMessage());
                        }
                    });
            return;
        }
        ItemStack current = player.getInventory().getItem(selection.inventorySlot());
        if (current == null || current.getType().isAir()) {
            player.getInventory().setItem(selection.inventorySlot(), selection.item().clone());
        } else {
            giveItem(player, selection.item());
        }
    }

    private ItemStack listingIcon(AuctionListing listing, List<String> configuredLore) {
        String price = plugin.economy().formatter().format(CurrencyType.MONEY, listing.price());
        String expires = remaining(listing.expiresAt());
        List<String> lore = new ArrayList<>();
        if (configuredLore.isEmpty()) {
            configuredLore = List.of(
                    "",
                    "<gray>Seller:</gray> <white>%seller%</white>",
                    "<gray>Price:</gray> <green>%price%</green>",
                    "<gray>Expires:</gray> <white>%expires%</white>",
                    "",
                    "<yellow>Click to buy</yellow>"
            );
        }
        for (String line : configuredLore) {
            lore.add(line
                    .replace("%seller%", listing.sellerName())
                    .replace("%price%", price)
                    .replace("%expires%", expires));
        }
        return appendLore(listing.item(), lore);
    }

    private ItemStack appendLore(ItemStack source, List<String> lines) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String line : lines) {
            lore.add(MINI.deserialize(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack controlItem(FileConfiguration config, String base, Material fallback, String fallbackName) {
        return controlItem(config, base, fallback, fallbackName, Map.of());
    }

    private ItemStack controlItem(
            FileConfiguration config,
            String base,
            Material fallback,
            String fallbackName,
            Map<String, String> replacements
    ) {
        Material itemMaterial = material(config.getString(base + ".material"), fallback);
        String name = replace(config.getString(base + ".name", fallbackName), replacements);
        List<String> lore = new ArrayList<>();
        for (String line : config.getStringList(base + ".lore")) {
            lore.add(replace(line, replacements));
        }
        return GuiItems.item(itemMaterial, name, lore.toArray(String[]::new));
    }

    private String replace(String input, Map<String, String> replacements) {
        String result = input;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return result;
    }

    private void fill(DuckGui gui, FileConfiguration config, String base) {
        if (!config.getBoolean(base + ".enabled", true)) {
            return;
        }
        Material fillerMaterial = material(config.getString(base + ".material"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = GuiItems.item(fillerMaterial, config.getString(base + ".name", " "));
        for (int slot = 0; slot < gui.getInventory().getSize(); slot++) {
            gui.setDisplay(slot, filler);
        }
    }

    private List<Integer> contentSlots(FileConfiguration config, String path, List<Integer> fallback) {
        List<Integer> configured = config.getIntegerList(path);
        return configured.isEmpty() ? fallback : configured;
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private String remaining(long expiresAt) {
        long millis = Math.max(0L, expiresAt - System.currentTimeMillis());
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(1L, minutes) + "m";
    }

    private String prettySort(AuctionSort sort) {
        return switch (sort) {
            case NEWEST -> "Newest";
            case OLDEST -> "Oldest";
            case PRICE_LOW -> "Price: Low to High";
            case PRICE_HIGH -> "Price: High to Low";
        };
    }

    private Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material found = Material.matchMaterial(raw);
        return found == null ? fallback : found;
    }

    private int clampRows(int rows) {
        return Math.max(1, Math.min(6, rows));
    }

    private boolean validSlot(DuckGui gui, int slot) {
        return slot >= 0 && slot < gui.getInventory().getSize();
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record ListingSelection(int inventorySlot, ItemStack item) {
        private ListingSelection {
            item = item.clone();
        }
    }

    private record MyItemsData(List<AuctionListing> listings, List<AuctionClaim> claims) {
    }
}
