package com.qducks.superducksystem.gui;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class GuiManager implements Listener {
    private final SuperDuckSystem plugin;

    public GuiManager(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DuckGui gui)) {
            return;
        }

        // Cancel every click while one of our menus is open. This also blocks shift-click,
        // number-key swaps and other inventory tricks from inserting items into protected GUI slots.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        GuiButton button = gui.button(event.getRawSlot());
        if (button == null) {
            return;
        }

        try {
            button.action().accept(new GuiClickContext(player, gui, event));
        } catch (Exception exception) {
            plugin.getLogger().severe("GUI click handler failed: " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DuckGui)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof DuckGui gui && event.getPlayer() instanceof Player player) {
            try {
                gui.handleClose(player);
            } catch (Exception exception) {
                plugin.getLogger().severe("GUI close handler failed: " + exception.getMessage());
            }
        }
    }
}
