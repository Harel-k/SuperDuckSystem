package com.qducks.superducksystem.order;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public record OrderClaim(
        UUID id,
        UUID orderId,
        UUID playerUuid,
        ItemStack item,
        int amount,
        long createdAt
) {
    public OrderClaim {
        item = item.clone();
        if (amount <= 0) {
            throw new IllegalArgumentException("Claim amount must be positive");
        }
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}
