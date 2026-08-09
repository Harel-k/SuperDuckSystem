package com.qducks.superducksystem.gui;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class DuckGui implements InventoryHolder {
    private final SuperDuckSystem plugin;
    private final Inventory inventory;
    private final Map<Integer, GuiButton> buttons = new HashMap<>();
    private Consumer<Player> closeHandler = player -> { };

    public DuckGui(SuperDuckSystem plugin, int rows, Component title) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("GUI rows must be between 1 and 6");
        }
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    public SuperDuckSystem plugin() {
        return plugin;
    }

    public DuckGui set(int slot, GuiButton button) {
        validateSlot(slot);
        buttons.put(slot, button);
        inventory.setItem(slot, button.icon());
        return this;
    }

    public DuckGui setDisplay(int slot, ItemStack icon) {
        return set(slot, GuiButton.display(icon));
    }

    public DuckGui clear(int slot) {
        validateSlot(slot);
        buttons.remove(slot);
        inventory.clear(slot);
        return this;
    }

    public GuiButton button(int slot) {
        return buttons.get(slot);
    }

    public DuckGui onClose(Consumer<Player> handler) {
        this.closeHandler = handler == null ? player -> { } : handler;
        return this;
    }

    void handleClose(Player player) {
        closeHandler.accept(player);
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void validateSlot(int slot) {
        if (slot < 0 || slot >= inventory.getSize()) {
            throw new IllegalArgumentException("Slot " + slot + " is outside GUI size " + inventory.getSize());
        }
    }
}
