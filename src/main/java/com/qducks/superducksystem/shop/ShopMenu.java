package com.qducks.superducksystem.shop;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.EconomyService;
import com.qducks.superducksystem.economy.TransactionType;
import com.qducks.superducksystem.gui.DuckGui;
import com.qducks.superducksystem.gui.GuiButton;
import com.qducks.superducksystem.gui.GuiItems;
import com.qducks.superducksystem.message.MessageService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ShopMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );

    private final SuperDuckSystem plugin;
    private final MessageService messages;

    public ShopMenu(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.messages = new MessageService(plugin);
    }

    public void open(Player player) {
        FileConfiguration config = plugin.configs().shop();
        int rows = clampRows(config.getInt("shop.menu.rows", 6));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("shop.menu.title", "<aqua><bold>Shop</bold></aqua>")));
        fill(gui, config, "shop.menu.filler");

        ConfigurationSection categories = config.getConfigurationSection("shop.categories");
        if (categories == null || categories.getKeys(false).isEmpty()) {
            messages.send(player, "shop.empty", "<gray>The shop has no configured categories.</gray>");
            return;
        }

        for (String categoryId : categories.getKeys(false)) {
            String base = "shop.categories." + categoryId;
            if (!config.getBoolean(base + ".enabled", true)) {
                continue;
            }
            int slot = config.getInt(base + ".slot", -1);
            if (slot < 0 || slot >= gui.getInventory().getSize()) {
                continue;
            }
            Material iconMaterial = material(config.getString(base + ".material"), Material.CHEST);
            String name = config.getString(base + ".name", "<white>" + pretty(categoryId) + "</white>");
            List<String> lore = config.getStringList(base + ".lore");
            ItemStack icon = GuiItems.item(iconMaterial, name, lore.toArray(String[]::new));
            gui.set(slot, new GuiButton(icon, context -> openCategory(context.player(), categoryId, 0)));
        }
        gui.open(player);
    }

    private void openCategory(Player player, String categoryId, int requestedPage) {
        FileConfiguration config = plugin.configs().shop();
        String categoryBase = "shop.categories." + categoryId;
        ConfigurationSection itemsSection = config.getConfigurationSection(categoryBase + ".items");
        if (itemsSection == null) {
            messages.send(player, "shop.category-empty", "<gray>That category is empty.</gray>");
            return;
        }

        List<String> itemIds = new ArrayList<>(itemsSection.getKeys(false));
        List<Integer> contentSlots = config.getIntegerList("shop.item-menu.content-slots");
        if (contentSlots.isEmpty()) {
            contentSlots = DEFAULT_CONTENT_SLOTS;
        }
        int perPage = Math.max(1, contentSlots.size());
        int pageCount = Math.max(1, (int) Math.ceil(itemIds.size() / (double) perPage));
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));

        int rows = clampRows(config.getInt("shop.item-menu.rows", 6));
        String categoryName = config.getString(categoryBase + ".plain-name", pretty(categoryId));
        String titleTemplate = config.getString("shop.item-menu.title", "<aqua><bold>%category%</bold></aqua> <gray>%page%/%pages%</gray>");
        String title = titleTemplate
                .replace("%category%", categoryName)
                .replace("%page%", Integer.toString(page + 1))
                .replace("%pages%", Integer.toString(pageCount));
        DuckGui gui = new DuckGui(plugin, rows, MINI.deserialize(title));
        fill(gui, config, "shop.item-menu.filler");

        int from = page * perPage;
        int to = Math.min(itemIds.size(), from + perPage);
        int displayIndex = 0;
        for (int index = from; index < to; index++) {
            String itemId = itemIds.get(index);
            String base = categoryBase + ".items." + itemId;
            if (!config.getBoolean(base + ".enabled", true)) {
                continue;
            }
            Material itemMaterial = material(config.getString(base + ".material", itemId), null);
            if (itemMaterial == null || itemMaterial.isAir()) {
                plugin.getLogger().warning("Invalid shop material for " + categoryId + "/" + itemId);
                continue;
            }
            int slot = contentSlots.get(displayIndex++);
            if (slot < 0 || slot >= gui.getInventory().getSize()) {
                continue;
            }
            BigDecimal unitPrice = buyPrice(base);
            String formatted = unitPrice.signum() > 0
                    ? plugin.economy().formatter().format(CurrencyType.MONEY, unitPrice)
                    : config.getString("shop.item-menu.not-for-sale", "<red>Not for sale</red>");
            String name = config.getString(base + ".name", "<white>" + pretty(itemMaterial.name()) + "</white>");
            List<String> lore = new ArrayList<>();
            List<String> configuredLore = config.getStringList(base + ".lore");
            if (!configuredLore.isEmpty()) {
                for (String line : configuredLore) {
                    lore.add(line.replace("%price%", formatted));
                }
            } else {
                lore.add(config.getString("shop.item-menu.price-line", "<gray>Buy:</gray> <green>%price%</green>").replace("%price%", formatted));
                if (unitPrice.signum() > 0) {
                    lore.add("");
                    lore.add(config.getString("shop.item-menu.click-line", "<yellow>Left-click: buy 1</yellow>"));
                    lore.add(config.getString("shop.item-menu.shift-click-line", "<yellow>Shift-left-click: buy %bulk%</yellow>")
                            .replace("%bulk%", Integer.toString(bulkAmount(config, itemMaterial))));
                }
            }

            ItemStack icon = GuiItems.item(itemMaterial, name, lore.toArray(String[]::new));
            if (unitPrice.signum() > 0) {
                gui.set(slot, new GuiButton(icon, context -> {
                    int amount = context.event().isShiftClick() ? bulkAmount(config, itemMaterial) : 1;
                    buy(context.player(), categoryId, page, itemMaterial, amount, unitPrice);
                }));
            } else {
                gui.setDisplay(slot, icon);
            }
        }

        int previousSlot = config.getInt("shop.item-menu.previous.slot", 45);
        if (page > 0 && validSlot(gui, previousSlot)) {
            Material previousMaterial = material(config.getString("shop.item-menu.previous.material"), Material.ARROW);
            ItemStack previous = GuiItems.item(previousMaterial,
                    config.getString("shop.item-menu.previous.name", "<yellow>Previous Page</yellow>"));
            int previousPage = page - 1;
            gui.set(previousSlot, new GuiButton(previous, context -> openCategory(context.player(), categoryId, previousPage)));
        }

        int backSlot = config.getInt("shop.item-menu.back.slot", 49);
        if (validSlot(gui, backSlot)) {
            Material backMaterial = material(config.getString("shop.item-menu.back.material"), Material.BARRIER);
            ItemStack back = GuiItems.item(backMaterial,
                    config.getString("shop.item-menu.back.name", "<red>Back</red>"));
            gui.set(backSlot, new GuiButton(back, context -> open(context.player())));
        }

        int nextSlot = config.getInt("shop.item-menu.next.slot", 53);
        if (page + 1 < pageCount && validSlot(gui, nextSlot)) {
            Material nextMaterial = material(config.getString("shop.item-menu.next.material"), Material.ARROW);
            ItemStack next = GuiItems.item(nextMaterial,
                    config.getString("shop.item-menu.next.name", "<yellow>Next Page</yellow>"));
            int nextPage = page + 1;
            gui.set(nextSlot, new GuiButton(next, context -> openCategory(context.player(), categoryId, nextPage)));
        }

        gui.open(player);
    }

    private void buy(Player player, String categoryId, int page, Material material, int amount, BigDecimal unitPrice) {
        if (amount <= 0) {
            return;
        }
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(amount));
        player.closeInventory();
        plugin.economy().take(
                player.getUniqueId(),
                CurrencyType.MONEY,
                total,
                TransactionType.SHOP_BUY,
                player.getUniqueId()
        ).whenComplete((newBalance, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                if (error == null) {
                    plugin.recoveries().queueMaterial(player.getUniqueId(), material, amount, "SHOP_BUY")
                            .whenComplete((ignored, recoveryError) -> {
                                if (recoveryError != null) {
                                    plugin.getLogger().severe("Could not persist an offline shop purchase for "
                                            + player.getUniqueId() + ": " + rootMessage(recoveryError));
                                }
                            });
                }
                return;
            }
            if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof EconomyService.InsufficientFundsException) {
                    messages.send(player, "shop.insufficient", "<red>You do not have enough money for that purchase.</red>");
                } else {
                    messages.send(player, "shop.failed", "<red>The purchase failed.</red>");
                }
                openCategory(player, categoryId, page);
                return;
            }
            give(player, material, amount);
            messages.send(player, "shop.bought", "<green>Bought <white>%amount%x %item%</white> for %price%.</green>", Map.of(
                    "amount", Integer.toString(amount),
                    "item", pretty(material.name()),
                    "price", plugin.economy().formatter().format(CurrencyType.MONEY, total)
            ));
            openCategory(player, categoryId, page);
        }));
    }

    private void give(Player player, Material material, int amount) {
        int left = amount;
        int maxStack = Math.max(1, material.getMaxStackSize());
        while (left > 0) {
            int size = Math.min(maxStack, left);
            ItemStack stack = new ItemStack(material, size);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            left -= size;
        }
    }

    private BigDecimal buyPrice(String itemBase) {
        String raw = plugin.configs().shop().getString(itemBase + ".buy-price", "-1");
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.signum() > 0 ? value : BigDecimal.ZERO;
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("Invalid shop buy price at " + itemBase + ": " + raw);
            return BigDecimal.ZERO;
        }
    }

    private int bulkAmount(FileConfiguration config, Material material) {
        int configured = config.getInt("shop.item-menu.bulk-buy-amount", 64);
        return Math.max(1, Math.min(configured, Math.max(1, material.getMaxStackSize())));
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

    private int clampRows(int rows) {
        return Math.max(1, Math.min(6, rows));
    }

    private boolean validSlot(DuckGui gui, int slot) {
        return slot >= 0 && slot < gui.getInventory().getSize();
    }

    private Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material found = Material.matchMaterial(raw);
        return found == null ? fallback : found;
    }

    private String pretty(String raw) {
        String[] words = raw.toLowerCase().replace('-', '_').split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    private Throwable unwrap(Throwable throwable) {
        return throwable.getCause() == null ? throwable : throwable.getCause();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
