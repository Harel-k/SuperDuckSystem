package com.qducks.superducksystem.crate;

import org.bukkit.Material;

import java.math.BigDecimal;

public record CrateReward(
        Type type,
        Material material,
        int itemAmount,
        BigDecimal currencyAmount,
        String keyId,
        int keyAmount,
        double weight
) {
    public enum Type {
        ITEM,
        MONEY,
        DUCKS,
        KEY
    }
}
