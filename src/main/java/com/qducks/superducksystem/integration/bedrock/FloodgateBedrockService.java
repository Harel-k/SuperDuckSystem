package com.qducks.superducksystem.integration.bedrock;

import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

public final class FloodgateBedrockService implements BedrockService {
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean isBedrock(Player player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }
}
