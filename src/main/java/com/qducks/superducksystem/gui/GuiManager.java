package com.qducks.superducksystem.gui;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.EnumSet;
import java.util.Set;

public final class GuiManager implements Listener {
    private static final Set<InventoryAction> SAFE_EDIT_ACTIONS = EnumSet.of(
            InventoryAction.PICKUP_ALL,
            InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE,
            InventoryAction.PICKUP_SOME,
            InventoryAction.PLACE_ALL,
            InventoryAction.PLACE_ONE,
            InventoryAction.PLACE_SOME,
            InventoryAction.SWAP_WITH_CURSOR
    );

    private final SuperDuckSystem plugin;

    public GuiManager(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DuckGui gui)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (gui instanceof EditableDuckGui editable
                && rawSlot >= 0
                && SAFE_EDIT_ACTIONS.contains(event.getAction())) {
            boolean playerInventorySlot = rawSlot >= topSize;
            boolean allowedTopSlot = rawSlot < topSize && editable.isEditable(rawSlot);
            if (playerInventorySlot || allowedTopSlot) {
                event.setCancelled(false);
                if (allowedTopSlot && event.getWhoClicked() instanceof Player player) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        try {
                            editable.handleEdit(player);
                        } catch (Exception exception) {
                            plugin.getLogger().severe("Editable GUI update handler failed: " + exception.getMessage());
                        }
                    });
                }
                return;
            }
        }

        // Everything else is protected: shift-click, number-key swaps, collect-to-cursor, drops,
        // protected top slots, and all shortcuts that could bypass the explicit input slot list.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        GuiButton button = gui.button(rawSlot);
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
