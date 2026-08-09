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

        // Editable GUIs are opt-in and only permit ordinary cursor pickup/place actions in explicitly
        // whitelisted top slots. Shift-clicks, hotbar swaps, drops and other shortcuts stay blocked.
        if (gui instanceof EditableDuckGui editable
                && rawSlot >= 0
                && rawSlot < topSize
                && editable.isEditable(rawSlot)
                && SAFE_EDIT_ACTIONS.contains(event.getAction())) {
            event.setCancelled(false);
            return;
        }

        // All regular DuckGui slots are protected. This also blocks shift-clicks and number-key
        // swaps from the player's inventory while one of our menus is open.
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
            // Deliberately blocked even for EditableDuckGui. Manual cursor placement is predictable;
            // drag distribution is much easier to exploit or mishandle in escrow-style interfaces.
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
