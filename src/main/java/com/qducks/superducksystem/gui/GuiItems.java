package com.qducks.superducksystem.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class GuiItems {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private GuiItems() {
    }

    public static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MINI.deserialize(name));
        if (lore.length > 0) {
            List<Component> lines = java.util.Arrays.stream(lore).map(MINI::deserialize).toList();
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }
}
