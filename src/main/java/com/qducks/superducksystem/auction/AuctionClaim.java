package com.qducks.superducksystem.auction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public record AuctionClaim(
        UUID id,
        UUID listingId,
        UUID playerUuid,
        ItemStack item,
        Reason reason,
        long createdAt
) {
    public AuctionClaim {
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    public enum Reason {
        PURCHASE,
        RETURN
    }
}
