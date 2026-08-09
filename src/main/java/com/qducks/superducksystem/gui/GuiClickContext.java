package com.qducks.superducksystem.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

public record GuiClickContext(Player player, DuckGui gui, InventoryClickEvent event) {
}
