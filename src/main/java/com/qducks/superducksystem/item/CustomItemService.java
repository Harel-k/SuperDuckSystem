package com.qducks.superducksystem.item;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

public final class CustomItemService {
    private final NamespacedKey itemIdKey;

    public CustomItemService(SuperDuckSystem plugin) {
        this.itemIdKey = new NamespacedKey(plugin, "item_id");
    }

    public ItemStack tag(ItemStack item, String itemId) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, itemId.toLowerCase());
        copy.setItemMeta(meta);
        return copy;
    }

    public @Nullable String getId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    }

    public boolean is(ItemStack item, String itemId) {
        String current = getId(item);
        return current != null && current.equalsIgnoreCase(itemId);
    }
}
