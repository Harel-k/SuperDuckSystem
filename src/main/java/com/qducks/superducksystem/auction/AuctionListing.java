package com.qducks.superducksystem.auction;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

public record AuctionListing(
        UUID id,
        UUID sellerUuid,
        String sellerName,
        ItemStack item,
        String searchText,
        BigDecimal price,
        long createdAt,
        long expiresAt,
        AuctionStatus status,
        UUID buyerUuid,
        Long soldAt
) {
    public AuctionListing {
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}
