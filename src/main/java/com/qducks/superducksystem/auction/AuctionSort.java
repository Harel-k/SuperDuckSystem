package com.qducks.superducksystem.auction;

public enum AuctionSort {
    NEWEST("created_at DESC"),
    OLDEST("created_at ASC"),
    PRICE_LOW("CAST(price AS REAL) ASC, created_at DESC"),
    PRICE_HIGH("CAST(price AS REAL) DESC, created_at DESC");

    private final String sqlOrder;

    AuctionSort(String sqlOrder) {
        this.sqlOrder = sqlOrder;
    }

    public String sqlOrder() {
        return sqlOrder;
    }

    public AuctionSort next() {
        AuctionSort[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
