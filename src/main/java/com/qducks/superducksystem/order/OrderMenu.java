package com.qducks.superducksystem.order;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrderMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35
    );

    private final SuperDuckSystem plugin;
    private final OrderService service;
    private final MessageService messages;

    public OrderMenu(SuperDuckSystem plugin, OrderService service) {
        this.plugin = plugin;
        this.service = service;
        this.messages = new MessageService(plugin);
    }

    public void open(Player player, String search, OrderSort sort, int page) {
        FileConfiguration config = plugin.configs().orders();
        List<Integer> slots = contentSlots(config, "menu.content-slots", DEFAULT_CONTENT_SLOTS);
        int pageSize = Math.min(Math.max(1, config.getInt("menu.page-size", 36)), Math.max(1, slots.size()));
        service.browse(search, sort, page, pageSize).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "order.load-failed", "<red>Could not load Buy Orders.</red>");
                        return;
                    }
                    renderBrowse(player, result, slots);
                })
        );
    }

    public void beginCreate(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            messages.send(player, "order.hold-item", "<red>Hold the item you want to order in your main hand.</red>");
            return;
        }

        ItemStack template = held.clone();
        template.setAmount(1);
        String defaultAmount = plugin.configs().orders().getString("creation.default-amount-input", "64");
        messages.send(player, "order.enter-amount", "<yellow>Enter the amount you want on the sign.</yellow>");
        plugin.signInput().request(player, defaultAmount, input ->
                Bukkit.getScheduler().runTask(plugin, () -> handleAmountInput(player, template, input))
        );
    }

    private void handleAmountInput(Player player, ItemStack template, String raw) {
        if (!player.isOnline()) {
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(raw.replace(",", "").trim());
            int max = Math.max(1, plugin.configs().orders().getInt("creation.maximum-amount", 1000000));
            if (amount <= 0 || amount > max) {
                throw new NumberFormatException("outside range");
            }
        } catch (NumberFormatException exception) {
            messages.send(player, "order.invalid-amount", "<red>Enter a whole number between 1 and %maximum%.</red>",
                    Map.of("maximum", Integer.toString(Math.max(1, plugin.configs().orders().getInt("creation.maximum-amount", 1000000)))));
            return;
        }

        String defaultPrice = plugin.configs().orders().getString("creation.default-price-input", "100");
        messages.send(player, "order.enter-price", "<yellow>Enter the price per item on the sign.</yellow>");
        plugin.signInput().request(player, defaultPrice, input ->
                Bukkit.getScheduler().runTask(plugin, () -> handlePriceInput(player, template, amount, input))
        );
    }

    private void handlePriceInput(Player player, ItemStack template, int amount, String raw) {
        if (!player.isOnline()) {
            return;
        }
        BigDecimal priceEach;
        try {
            priceEach = plugin.economy().formatter().normalize(
                    CurrencyType.MONEY,
                    new BigDecimal(raw.replace(",", "").trim())
            );
            BigDecimal min = new BigDecimal(plugin.configs().orders().getString("creation.minimum-price-each", "1"));
            BigDecimal max = new BigDecimal(plugin.configs().orders().getString("creation.maximum-price-each", "1000000000000"));
            if (priceEach.signum() <= 0 || priceEach.compareTo(min) < 0 || priceEach.compareTo(max) > 0) {
                throw new NumberFormatException("outside range");
            }
        } catch (NumberFormatException exception) {
            messages.send(player, "order.invalid-price", "<red>That is not a valid price per item.</red>");
            return;
        }

        if (plugin.settings().get(player.getUniqueId(), PlayerSetting.ORDER_CREATE_CONFIRMATION)) {
            openCreateConfirmation(player, template, amount, priceEach);
        } else {
            createOrder(player, template, amount, priceEach);
        }
    }

    private void renderBrowse(Player player, OrderService.OrderPage page, List<Integer> contentSlots) {
        FileConfiguration config = plugin.configs().orders();
        int rows = clampRows(config.getInt("menu.rows", 6));
        String title = config.getString("menu.title", "<aqua><bold>Buy Orders</bold></aqua> <gray>%page%/%pages%</gray>")
                .replace("%page%", Integer.toString(page.page() + 1))
                .replace("%pages%", Integer.toString(page.pages()));
        DuckGui gui = new DuckGui(plugin, rows, MINI.deserialize(title));
        fill(gui, config, "menu.filler");

        int index = 0;
        for (OrderListing order : page.listings()) {
            if (index >= contentSlots.size()) {
                break;
            }
            int slot = contentSlots.get(index++);
            if (!validSlot(gui, slot)) {
                continue;
            }
            ItemStack icon = orderIcon(order, config.getStringList("menu.listing-lore"));
            gui.set(slot, new GuiButton(icon, context -> openFillConfirmation(context.player(), order)));
        }

        int previousSlot = config.getInt("menu.previous.slot", 45);
        if (page.page() > 0 && validSlot(gui, previousSlot)) {
            gui.set(previousSlot, new GuiButton(
                    controlItem(config, "menu.previous", Material.ARROW, "<yellow>Previous Page</yellow>"),
                    context -> open(context.player(), page.search(), page.sort(), page.page() - 1)
            ));
        }

        int searchSlot = config.getInt("menu.search.slot", 46);
        if (validSlot(gui, searchSlot)) {
            String currentSearch = page.search().isBlank() ? "All Items" : page.search();
            ItemStack search = controlItem(config, "menu.search", Material.OAK_SIGN, "<aqua><bold>Search</bold></aqua>",
                    Map.of("search", currentSearch));
            gui.set(searchSlot, new GuiButton(search, context -> {
                Player searchPlayer = context.player();
                plugin.signInput().request(searchPlayer, page.search(), input ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (searchPlayer.isOnline()) {
                                open(searchPlayer, input, page.sort(), 0);
                            }
                        })
                );
            }));
        }

        int myOrdersSlot = config.getInt("menu.my-orders.slot", 47);
        if (validSlot(gui, myOrdersSlot)) {
            gui.set(myOrdersSlot, new GuiButton(
                    controlItem(config, "menu.my-orders", Material.CHEST, "<gold><bold>My Orders</bold></gold>"),
                    context -> openMyOrders(context.player())
            ));
        }

        int refreshSlot = config.getInt("menu.refresh.slot", 49);
        if (validSlot(gui, refreshSlot)) {
            gui.set(refreshSlot, new GuiButton(
                    controlItem(config, "menu.refresh", Material.SUNFLOWER, "<green><bold>Refresh</bold></green>"),
                    context -> open(context.player(), page.search(), page.sort(), page.page())
            ));
        }

        int sortSlot = config.getInt("menu.sort.slot", 50);
        if (validSlot(gui, sortSlot)) {
            gui.set(sortSlot, new GuiButton(
                    controlItem(config, "menu.sort", Material.HOPPER, "<yellow><bold>Sort</bold></yellow>",
                            Map.of("sort", prettySort(page.sort()))),
                    context -> open(context.player(), page.search(), page.sort().next(), 0)
            ));
        }

        int createSlot = config.getInt("menu.create.slot", 51);
        if (validSlot(gui, createSlot)) {
            gui.set(createSlot, new GuiButton(
                    controlItem(config, "menu.create", Material.EMERALD, "<green><bold>Create Order</bold></green>"),
                    context -> beginCreate(context.player())
            ));
        }

        int clearSlot = config.getInt("menu.clear-search.slot", 52);
        if (!page.search().isBlank() && validSlot(gui, clearSlot)) {
            gui.set(clearSlot, new GuiButton(
                    controlItem(config, "menu.clear-search", Material.MILK_BUCKET, "<red>Clear Search</red>"),
                    context -> open(context.player(), "", page.sort(), 0)
            ));
        }

        int nextSlot = config.getInt("menu.next.slot", 53);
        if (page.page() + 1 < page.pages() && validSlot(gui, nextSlot)) {
            gui.set(nextSlot, new GuiButton(
                    controlItem(config, "menu.next", Material.ARROW, "<yellow>Next Page</yellow>"),
                    context -> open(context.player(), page.search(), page.sort(), page.page() + 1)
            ));
        }

        gui.open(player);
    }

    private void openCreateConfirmation(Player player, ItemStack template, int amount, BigDecimal priceEach) {
        FileConfiguration config = plugin.configs().orders();
        DuckGui gui = confirmationGui(config, "confirmation.create-title", "<aqua><bold>Confirm Order</bold></aqua>");
        BigDecimal total = priceEach.multiply(BigDecimal.valueOf(amount));
        int itemSlot = config.getInt("confirmation.item-slot", 13);
        if (validSlot(gui, itemSlot)) {
            gui.setDisplay(itemSlot, appendLore(template, List.of(
                    "",
                    "<gray>Amount:</gray> <white>" + amount + "</white>",
                    "<gray>Price each:</gray> <green>" + money(priceEach) + "</green>",
                    "<gray>Final cost:</gray> <green>" + money(total) + "</green>",
                    "<yellow>The full final cost is escrowed immediately.</yellow>"
            )));
        }

        int confirmSlot = config.getInt("confirmation.confirm-slot", 11);
        if (validSlot(gui, confirmSlot)) {
            gui.set(confirmSlot, new GuiButton(confirmItem(config),
                    context -> createOrder(context.player(), template, amount, priceEach)));
        }
        int cancelSlot = config.getInt("confirmation.cancel-slot", 15);
        if (validSlot(gui, cancelSlot)) {
            gui.set(cancelSlot, new GuiButton(cancelItem(config), context -> context.player().closeInventory()));
        }
        gui.open(player);
    }

    private void createOrder(Player player, ItemStack template, int amount, BigDecimal priceEach) {
        player.closeInventory();
        service.createOrder(player.getUniqueId(), player.getName(), template, amount, priceEach, service.slotLimit(player))
                .whenComplete((order, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        if (cause instanceof EconomyService.InsufficientFundsException) {
                            messages.send(player, "order.insufficient", "<red>You do not have enough money for that order.</red>");
                        } else if (cause instanceof OrderService.SlotLimitException limit) {
                            messages.send(player, "order.slot-limit", "<red>You are using all %limit% of your order slots.</red>",
                                    Map.of("limit", Integer.toString(limit.limit())));
                        } else if (cause instanceof OrderService.AmountRangeException amountError) {
                            messages.send(player, "order.invalid-amount", "<red>Order amount must be between 1 and %maximum%.</red>",
                                    Map.of("maximum", Integer.toString(amountError.maximum())));
                        } else if (cause instanceof OrderService.PriceRangeException range) {
                            messages.send(player, "order.price-range", "<red>Price each must be between %minimum% and %maximum%.</red>", Map.of(
                                    "minimum", money(range.minimum()),
                                    "maximum", money(range.maximum())
                            ));
                        } else {
                            messages.send(player, "order.create-failed", "<red>Could not create that order.</red>");
                        }
                        return;
                    }
                    messages.send(player, "order.created", "<green>Created an order for <white>%amount%x</white> at %price_each% each. Escrow: %total%.</green>", Map.of(
                            "amount", Integer.toString(order.totalAmount()),
                            "price_each", money(order.priceEach()),
                            "total", money(order.totalCost())
                    ));
                }));
    }

    private void openFillConfirmation(Player player, OrderListing order) {
        if (order.buyerUuid().equals(player.getUniqueId())) {
            messages.send(player, "order.own-order", "<red>You cannot fill your own order.</red>");
            return;
        }
        int available = countMatching(player.getInventory(), order.item());
        int amount = Math.min(available, order.remainingAmount());
        if (amount <= 0) {
            messages.send(player, "order.no-items", "<red>You do not have the requested item.</red>");
            return;
        }

        FileConfiguration config = plugin.configs().orders();
        DuckGui gui = confirmationGui(config, "confirmation.fill-title", "<green><bold>Fill Order</bold></green>");
        BigDecimal payout = order.priceEach().multiply(BigDecimal.valueOf(amount));
        int itemSlot = config.getInt("confirmation.item-slot", 13);
        if (validSlot(gui, itemSlot)) {
            gui.setDisplay(itemSlot, appendLore(order.item(), List.of(
                    "",
                    "<gray>You can sell:</gray> <white>" + amount + "</white>",
                    "<gray>Price each:</gray> <green>" + money(order.priceEach()) + "</green>",
                    "<gray>You receive:</gray> <green>" + money(payout) + "</green>",
                    "<gray>Order remaining:</gray> <white>" + order.remainingAmount() + "</white>"
            )));
        }
        int confirmSlot = config.getInt("confirmation.confirm-slot", 11);
        if (validSlot(gui, confirmSlot)) {
            gui.set(confirmSlot, new GuiButton(confirmItem(config), context -> fillOrder(context.player(), order, amount)));
        }
        int cancelSlot = config.getInt("confirmation.cancel-slot", 15);
        if (validSlot(gui, cancelSlot)) {
            gui.set(cancelSlot, new GuiButton(cancelItem(config),
                    context -> open(context.player(), "", OrderSort.NEWEST, 0)));
        }
        gui.open(player);
    }

    private void fillOrder(Player seller, OrderListing order, int amount) {
        List<ItemStack> removed = removeMatching(seller.getInventory(), order.item(), amount);
        int removedCount = removed.stream().mapToInt(ItemStack::getAmount).sum();
        if (removedCount != amount) {
            restoreItems(seller, removed);
            messages.send(seller, "order.items-changed", "<red>Your inventory changed. Try filling the order again.</red>");
            return;
        }
        seller.closeInventory();

        service.fill(seller.getUniqueId(), seller.getName(), order.id(), amount)
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        restoreItems(seller, removed);
                        Throwable cause = unwrap(error);
                        if (cause instanceof OrderService.OrderUnavailableException
                                || cause instanceof OrderService.FillAmountException) {
                            messages.send(seller, "order.unavailable", "<red>That order changed or is no longer available.</red>");
                        } else {
                            messages.send(seller, "order.fill-failed", "<red>The order fill failed, so your items were returned.</red>");
                        }
                        return;
                    }

                    if (seller.isOnline()) {
                        messages.send(seller, "order.filled", "<green>Sold <white>%amount%x</white> into the order for %payout%.</green>", Map.of(
                                "amount", Integer.toString(result.amount()),
                                "payout", money(result.payout())
                        ));
                    }

                    Player buyer = Bukkit.getPlayer(result.order().buyerUuid());
                    if (buyer != null && plugin.settings().get(buyer.getUniqueId(), PlayerSetting.ORDER_NOTIFICATIONS)) {
                        messages.send(buyer, "order.buyer-filled", "<green>Your order received <white>%amount%x</white>. Claim it in My Orders.</green>",
                                Map.of("amount", Integer.toString(result.amount())));
                    }
                }));
    }

    private void openMyOrders(Player player) {
        var ordersFuture = service.buyerOrders(player.getUniqueId());
        var claimsFuture = service.pendingClaims(player.getUniqueId());
        ordersFuture.thenCombine(claimsFuture, MyOrdersData::new).whenComplete((data, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "order.load-failed", "<red>Could not load your orders.</red>");
                        return;
                    }
                    renderMyOrders(player, data);
                })
        );
    }

    private void renderMyOrders(Player player, MyOrdersData data) {
        FileConfiguration config = plugin.configs().orders();
        int rows = clampRows(config.getInt("my-orders.rows", 6));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("my-orders.title", "<gold><bold>My Orders</bold></gold>")));
        fill(gui, config, "my-orders.filler");
        List<Integer> slots = contentSlots(config, "my-orders.content-slots", DEFAULT_CONTENT_SLOTS);
        int index = 0;

        for (OrderClaim claim : data.claims()) {
            if (index >= slots.size()) {
                break;
            }
            int slot = slots.get(index++);
            ItemStack icon = appendLore(claim.item(), List.of(
                    "",
                    "<green><bold>READY TO CLAIM</bold></green>",
                    "<gray>Amount:</gray> <white>" + claim.amount() + "</white>",
                    "<yellow>Click to claim.</yellow>"
            ));
            gui.set(slot, new GuiButton(icon, context -> claimItems(context.player(), claim)));
        }

        for (OrderListing order : data.orders()) {
            if (index >= slots.size()) {
                break;
            }
            int slot = slots.get(index++);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("<gray>Status:</gray> <white>" + order.status().name() + "</white>");
            lore.add("<gray>Remaining:</gray> <white>" + order.remainingAmount() + " / " + order.totalAmount() + "</white>");
            lore.add("<gray>Price each:</gray> <green>" + money(order.priceEach()) + "</green>");
            if (order.status() == OrderStatus.OPEN) {
                lore.add("<gray>Escrow remaining:</gray> <green>" + money(order.escrowRemaining()) + "</green>");
                lore.add("");
                lore.add("<yellow>Click to cancel and refund unused escrow.</yellow>");
            }
            ItemStack icon = appendLore(order.item(), lore);
            if (order.status() == OrderStatus.OPEN) {
                gui.set(slot, new GuiButton(icon, context -> cancelOrder(context.player(), order)));
            } else {
                gui.setDisplay(slot, icon);
            }
        }

        int backSlot = config.getInt("my-orders.back.slot", 49);
        if (validSlot(gui, backSlot)) {
            gui.set(backSlot, new GuiButton(
                    controlItem(config, "my-orders.back", Material.BARRIER, "<red>Back</red>"),
                    context -> open(context.player(), "", OrderSort.NEWEST, 0)
            ));
        }
        gui.open(player);
    }

    private void cancelOrder(Player player, OrderListing order) {
        player.closeInventory();
        service.cancel(player.getUniqueId(), order.id()).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "order.cancel-failed", "<red>Could not cancel that order.</red>");
                        return;
                    }
                    messages.send(player, "order.cancelled", "<green>Order cancelled. Refunded %refund%.</green>",
                            Map.of("refund", money(result.refund())));
                    openMyOrders(player);
                })
        );
    }

    private void claimItems(Player player, OrderClaim claim) {
        player.closeInventory();
        service.claim(player.getUniqueId(), claim.id()).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "order.claim-failed", "<yellow>Those items are still waiting in My Orders.</yellow>");
                        return;
                    }
                    giveItemAmount(player, result.item(), result.amount());
                    messages.send(player, "order.claimed", "<green>Claimed <white>%amount%x</white> from your order.</green>",
                            Map.of("amount", Integer.toString(result.amount())));
                })
        );
    }

    private DuckGui confirmationGui(FileConfiguration config, String titlePath, String fallbackTitle) {
        int rows = clampRows(config.getInt("confirmation.rows", 3));
        DuckGui gui = new DuckGui(plugin, rows, MINI.deserialize(config.getString(titlePath, fallbackTitle)));
        fill(gui, config, "confirmation.filler");
        return gui;
    }

    private ItemStack confirmItem(FileConfiguration config) {
        Material material = material(config.getString("confirmation.confirm-material"), Material.LIME_CONCRETE);
        return GuiItems.item(material, config.getString("confirmation.confirm-name", "<green><bold>Confirm</bold></green>"));
    }

    private ItemStack cancelItem(FileConfiguration config) {
        Material material = material(config.getString("confirmation.cancel-material"), Material.RED_CONCRETE);
        return GuiItems.item(material, config.getString("confirmation.cancel-name", "<red><bold>Cancel</bold></red>"));
    }

    private ItemStack orderIcon(OrderListing order, List<String> configuredLore) {
        List<String> lore = new ArrayList<>();
        if (configuredLore.isEmpty()) {
            configuredLore = List.of(
                    "",
                    "<gray>Buyer:</gray> <white>%buyer%</white>",
                    "<gray>Price each:</gray> <green>%price_each%</green>",
                    "<gray>Remaining:</gray> <white>%remaining% / %total%</white>",
                    "<yellow>Click to fill this order.</yellow>"
            );
        }
        for (String line : configuredLore) {
            lore.add(line
                    .replace("%buyer%", order.buyerName())
                    .replace("%price_each%", money(order.priceEach()))
                    .replace("%remaining%", Integer.toString(order.remainingAmount()))
                    .replace("%total%", Integer.toString(order.totalAmount()))
                    .replace("%remaining_value%", money(order.escrowRemaining())));
        }
        return appendLore(order.item(), lore);
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

    private int countMatching(PlayerInventory inventory, ItemStack template) {
        int count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (service.matches(item, template)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private List<ItemStack> removeMatching(PlayerInventory inventory, ItemStack template, int requested) {
        int left = requested;
        List<ItemStack> removed = new ArrayList<>();
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack current = contents[slot];
            if (!service.matches(current, template)) {
                continue;
            }
            int take = Math.min(left, current.getAmount());
            ItemStack removedStack = current.clone();
            removedStack.setAmount(take);
            removed.add(removedStack);
            if (take == current.getAmount()) {
                contents[slot] = null;
            } else {
                ItemStack remaining = current.clone();
                remaining.setAmount(current.getAmount() - take);
                contents[slot] = remaining;
            }
            left -= take;
        }
        inventory.setStorageContents(contents);
        return removed;
    }

    private void restoreItems(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            // The removed items only live in memory at this point. This branch is intentionally
            // conservative during development; disconnect/crash recovery will be hardened before v1.
            return;
        }
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void giveItemAmount(Player player, ItemStack template, int amount) {
        int left = amount;
        int maxStack = Math.max(1, template.getMaxStackSize());
        while (left > 0) {
            int size = Math.min(left, maxStack);
            ItemStack stack = template.clone();
            stack.setAmount(size);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            left -= size;
        }
    }

    private String money(BigDecimal amount) {
        return plugin.economy().formatter().format(CurrencyType.MONEY, amount);
    }

    private List<Integer> contentSlots(FileConfiguration config, String path, List<Integer> fallback) {
        List<Integer> configured = config.getIntegerList(path);
        return configured.isEmpty() ? fallback : configured;
    }

    private String prettySort(OrderSort sort) {
        return switch (sort) {
            case NEWEST -> "Newest";
            case OLDEST -> "Oldest";
            case PRICE_LOW -> "Price: Low to High";
            case PRICE_HIGH -> "Price: High to Low";
            case REMAINING_HIGH -> "Most Remaining";
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

    private record MyOrdersData(List<OrderListing> orders, List<OrderClaim> claims) {
    }
}
