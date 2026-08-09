package com.qducks.superducksystem.shop;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.TransactionType;
import com.qducks.superducksystem.gui.EditableDuckGui;
import com.qducks.superducksystem.gui.GuiButton;
import com.qducks.superducksystem.gui.GuiItems;
import com.qducks.superducksystem.message.MessageService;
import com.qducks.superducksystem.settings.PlayerSetting;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SellMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SuperDuckSystem plugin;
    private final ShopService shop;
    private final MessageService messages;

    public SellMenu(SuperDuckSystem plugin, ShopService shop) {
        this.plugin = plugin;
        this.shop = shop;
        this.messages = new MessageService(plugin);
    }

    public void open(Player player) {
        FileConfiguration config = plugin.configs().shop();
        int rows = Math.max(3, Math.min(6, config.getInt("sell.menu.rows", 6)));
        String title = config.getString("sell.menu.title", "<green><bold>Sell Items</bold></green>");
        EditableDuckGui gui = new EditableDuckGui(plugin, rows, MINI.deserialize(title));
        SellSession session = new SellSession(player, gui, inputSlots(config, rows));

        Material fillerMaterial = material(config.getString("sell.menu.filler.material"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = GuiItems.item(fillerMaterial, config.getString("sell.menu.filler.name", " "));
        for (int slot = 0; slot < rows * 9; slot++) {
            if (!session.inputSlots.contains(slot)) {
                gui.setDisplay(slot, filler);
            }
        }
        for (int slot : session.inputSlots) {
            gui.clear(slot);
            gui.allowInput(slot);
        }

        gui.onEdit(editedPlayer -> {
            session.armed = false;
            refresh(session);
        });
        gui.onClose(closedPlayer -> returnRemaining(session));

        refresh(session);
        gui.open(player);
    }

    private void refresh(SellSession session) {
        if (session.processing || !session.player.isOnline()) {
            return;
        }
        FileConfiguration config = plugin.configs().shop();
        Quote quote = quote(session);

        int summarySlot = config.getInt("sell.menu.summary.slot", 45);
        if (validSlot(session, summarySlot) && !session.inputSlots.contains(summarySlot)) {
            Material summaryMaterial = material(config.getString("sell.menu.summary.material"), Material.PAPER);
            String total = plugin.economy().formatter().format(CurrencyType.MONEY, quote.total);
            ItemStack summary = GuiItems.item(
                    summaryMaterial,
                    config.getString("sell.menu.summary.name", "<aqua><bold>Sale Summary</bold></aqua>"),
                    config.getString("sell.menu.summary.value-line", "<gray>Value:</gray> <green>%value%</green>").replace("%value%", total),
                    config.getString("sell.menu.summary.items-line", "<gray>Sellable items:</gray> <white>%items%</white>").replace("%items%", Integer.toString(quote.itemCount)),
                    config.getString("sell.menu.summary.unsellable-line", "<gray>Unsellable items:</gray> <red>%unsellable%</red>").replace("%unsellable%", Integer.toString(quote.unsellableCount))
            );
            session.gui.setDisplay(summarySlot, summary);
        }

        int confirmSlot = config.getInt("sell.menu.confirm.slot", 49);
        if (validSlot(session, confirmSlot) && !session.inputSlots.contains(confirmSlot)) {
            boolean instant = plugin.settings().get(session.player.getUniqueId(), PlayerSetting.INSTANT_SELL);
            Material confirmMaterial = material(config.getString("sell.menu.confirm.material"), Material.EMERALD_BLOCK);
            String name;
            if (instant) {
                name = config.getString("sell.menu.confirm.instant-name", "<green><bold>Sell Now</bold></green>");
            } else if (session.armed) {
                name = config.getString("sell.menu.confirm.armed-name", "<green><bold>Confirm Sale</bold></green>");
            } else {
                name = config.getString("sell.menu.confirm.name", "<yellow><bold>Sell Items</bold></yellow>");
            }
            String total = plugin.economy().formatter().format(CurrencyType.MONEY, quote.total);
            ItemStack confirm = GuiItems.item(
                    confirmMaterial,
                    name,
                    config.getString("sell.menu.confirm.value-line", "<gray>You will receive:</gray> <green>%value%</green>").replace("%value%", total),
                    instant
                            ? config.getString("sell.menu.confirm.instant-line", "<yellow>Click to sell immediately.</yellow>")
                            : session.armed
                            ? config.getString("sell.menu.confirm.armed-line", "<green>Click again to confirm.</green>")
                            : config.getString("sell.menu.confirm.line", "<yellow>Click to continue.</yellow>")
            );
            session.gui.set(confirmSlot, new GuiButton(confirm, context -> handleSell(session)));
        }

        int cancelSlot = config.getInt("sell.menu.cancel.slot", 53);
        if (validSlot(session, cancelSlot) && !session.inputSlots.contains(cancelSlot)) {
            Material cancelMaterial = material(config.getString("sell.menu.cancel.material"), Material.BARRIER);
            ItemStack cancel = GuiItems.item(
                    cancelMaterial,
                    config.getString("sell.menu.cancel.name", "<red><bold>Cancel</bold></red>"),
                    config.getString("sell.menu.cancel.lore", "<gray>Return your items and close.</gray>")
            );
            session.gui.set(cancelSlot, new GuiButton(cancel, context -> context.player().closeInventory()));
        }
    }

    private void handleSell(SellSession session) {
        if (session.processing) {
            return;
        }
        Quote quote = quote(session);
        if (quote.itemCount <= 0 || quote.total.signum() <= 0) {
            messages.send(session.player, "sell.nothing", "<red>There are no sellable items in the menu.</red>");
            return;
        }

        boolean instant = plugin.settings().get(session.player.getUniqueId(), PlayerSetting.INSTANT_SELL);
        if (!instant && !session.armed) {
            session.armed = true;
            refresh(session);
            return;
        }

        session.processing = true;
        List<ItemStack> removed = new ArrayList<>();
        BigDecimal finalTotal = BigDecimal.ZERO;
        int finalItems = 0;

        for (int slot : session.inputSlots) {
            ItemStack item = session.gui.getInventory().getItem(slot);
            if (!shop.isSellable(item)) {
                continue;
            }
            ItemStack copy = item.clone();
            removed.add(copy);
            finalTotal = finalTotal.add(shop.stackSellValue(copy));
            finalItems += copy.getAmount();
            session.gui.getInventory().clear(slot);
        }

        if (finalTotal.signum() <= 0 || removed.isEmpty()) {
            session.processing = false;
            refresh(session);
            return;
        }

        BigDecimal payout = finalTotal;
        int soldItems = finalItems;
        session.player.closeInventory();

        plugin.economy().add(
                session.player.getUniqueId(),
                CurrencyType.MONEY,
                payout,
                TransactionType.SHOP_SELL,
                session.player.getUniqueId()
        ).whenComplete((newBalance, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                returnItems(session.player, removed);
                messages.send(session.player, "sell.failed", "<red>The sale failed, so your items were returned.</red>");
                return;
            }
            messages.send(session.player, "sell.success", "<green>Sold <white>%items%</white> items for %amount%.</green>", Map.of(
                    "items", Integer.toString(soldItems),
                    "amount", plugin.economy().formatter().format(CurrencyType.MONEY, payout)
            ));
        }));
    }

    private Quote quote(SellSession session) {
        BigDecimal total = BigDecimal.ZERO;
        int itemCount = 0;
        int unsellableCount = 0;
        for (int slot : session.inputSlots) {
            ItemStack item = session.gui.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (shop.isSellable(item)) {
                total = total.add(shop.stackSellValue(item));
                itemCount += item.getAmount();
            } else {
                unsellableCount += item.getAmount();
            }
        }
        return new Quote(total, itemCount, unsellableCount);
    }

    private void returnRemaining(SellSession session) {
        for (int slot : session.inputSlots) {
            ItemStack item = session.gui.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            session.gui.getInventory().clear(slot);
            returnItems(session.player, List.of(item));
        }
    }

    private void returnItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private Set<Integer> inputSlots(FileConfiguration config, int rows) {
        List<Integer> configured = config.getIntegerList("sell.menu.input-slots");
        if (configured.isEmpty()) {
            configured = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34);
        }
        Set<Integer> result = new HashSet<>();
        for (int slot : configured) {
            if (slot >= 0 && slot < rows * 9) {
                result.add(slot);
            }
        }
        return result;
    }

    private boolean validSlot(SellSession session, int slot) {
        return slot >= 0 && slot < session.gui.getInventory().getSize();
    }

    private Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material found = Material.matchMaterial(raw);
        return found == null ? fallback : found;
    }

    private static final class SellSession {
        private final Player player;
        private final EditableDuckGui gui;
        private final Set<Integer> inputSlots;
        private boolean armed;
        private boolean processing;

        private SellSession(Player player, EditableDuckGui gui, Set<Integer> inputSlots) {
            this.player = player;
            this.gui = gui;
            this.inputSlots = inputSlots;
        }
    }

    private record Quote(BigDecimal total, int itemCount, int unsellableCount) {
    }
}
