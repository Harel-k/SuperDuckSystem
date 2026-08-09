package com.qducks.superducksystem.economy;

public enum CurrencyType {
    MONEY("money"),
    DUCKS("ducks");

    private final String configKey;

    CurrencyType(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }
}
