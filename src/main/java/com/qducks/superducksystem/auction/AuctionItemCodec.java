package com.qducks.superducksystem.auction;

import org.bukkit.inventory.ItemStack;

public final class AuctionItemCodec {
    private AuctionItemCodec() {
    }

    public static byte[] encode(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            throw new IllegalArgumentException("Auction item cannot be empty");
        }
        return item.serializeAsBytes();
    }

    public static ItemStack decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Auction item data cannot be empty");
        }
        return ItemStack.deserializeBytes(data);
    }
}
