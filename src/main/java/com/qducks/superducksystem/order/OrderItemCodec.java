package com.qducks.superducksystem.order;

import org.bukkit.inventory.ItemStack;

public final class OrderItemCodec {
    private OrderItemCodec() {
    }

    public static byte[] encode(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            throw new IllegalArgumentException("Order item cannot be empty");
        }
        return item.serializeAsBytes();
    }

    public static ItemStack decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Order item data cannot be empty");
        }
        return ItemStack.deserializeBytes(data);
    }
}
