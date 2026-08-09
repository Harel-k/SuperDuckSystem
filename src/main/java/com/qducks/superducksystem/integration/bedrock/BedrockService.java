package com.qducks.superducksystem.integration.bedrock;

import org.bukkit.entity.Player;

import java.util.function.Consumer;

public interface BedrockService {
    boolean available();

    boolean isBedrock(Player player);

    boolean openSettings(Player player);

    /** Opens a native Bedrock text-input form when available. */
    boolean requestText(Player player, String title, String label, String placeholder, String defaultValue, Consumer<String> callback);
}
