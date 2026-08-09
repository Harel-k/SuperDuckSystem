package com.qducks.superducksystem.order;

public enum OrderSort {
    NEWEST("created_at DESC"),
    OLDEST("created_at ASC"),
    PRICE_LOW("CAST(price_each AS REAL) ASC, created_at DESC"),
    PRICE_HIGH("CAST(price_each AS REAL) DESC, created_at DESC"),
    REMAINING_HIGH("remaining_amount DESC, created_at DESC");

    private final String sqlOrder;

    OrderSort(String sqlOrder) {
        this.sqlOrder = sqlOrder;
    }

    public String sqlOrder() {
        return sqlOrder;
    }

    public OrderSort next() {
        OrderSort[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
