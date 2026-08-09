package com.qducks.superducksystem.settings;

public enum PlayerSetting {
    PAY_NOTIFICATIONS("pay-notifications", true),
    AUCTION_NOTIFICATIONS("auction-notifications", true),
    ORDER_NOTIFICATIONS("order-notifications", true),
    CRATE_BROADCASTS("crate-broadcasts", true),
    SOUNDS("sounds", true),
    PARTICLES("particles", true),
    AUCTION_SELL_CONFIRMATION("auction-sell-confirmation", true),
    AUCTION_BUY_CONFIRMATION("auction-buy-confirmation", true),
    ORDER_CREATE_CONFIRMATION("order-create-confirmation", true),
    INSTANT_AUCTION_PURCHASE("instant-auction-purchase", false);

    private final String configKey;
    private final boolean fallback;

    PlayerSetting(String configKey, boolean fallback) {
        this.configKey = configKey;
        this.fallback = fallback;
    }

    public String configKey() {
        return configKey;
    }

    public boolean fallback() {
        return fallback;
    }
}
