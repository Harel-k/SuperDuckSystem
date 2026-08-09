package com.qducks.superducksystem.order;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderListing(
        UUID id,
        UUID buyerUuid,
        String buyerName,
        ItemStack item,
        String searchText,
        int totalAmount,
        int remainingAmount,
        BigDecimal priceEach,
        BigDecimal escrowRemaining,
        long createdAt,
        OrderStatus status
) {
    public OrderListing {
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    public BigDecimal totalCost() {
        return priceEach.multiply(BigDecimal.valueOf(totalAmount));
    }

    public BigDecimal remainingCost() {
        return priceEach.multiply(BigDecimal.valueOf(remainingAmount));
    }
}
