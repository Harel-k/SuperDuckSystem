package com.qducks.superducksystem.integration.bedrock;

import org.bukkit.entity.Player;

public interface BedrockService {
    boolean available();

    boolean isBedrock(Player player);
}
