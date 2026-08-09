package com.qducks.superducksystem.gui;

import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

public record GuiButton(ItemStack icon, Consumer<GuiClickContext> action) {
    public GuiButton {
        icon = icon.clone();
    }

    public static GuiButton display(ItemStack icon) {
        return new GuiButton(icon, context -> { });
    }
}
