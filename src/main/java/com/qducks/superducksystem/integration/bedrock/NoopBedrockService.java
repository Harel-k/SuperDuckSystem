package com.qducks.superducksystem.integration.bedrock;

import org.bukkit.entity.Player;

public final class NoopBedrockService implements BedrockService {
    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean isBedrock(Player player) {
        return false;
    }

    @Override
    public boolean openSettings(Player player) {
        return false;
    }
}
