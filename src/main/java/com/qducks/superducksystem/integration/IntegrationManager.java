package com.qducks.superducksystem.integration;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.integration.bedrock.BedrockService;
import com.qducks.superducksystem.integration.bedrock.FloodgateBedrockService;
import com.qducks.superducksystem.integration.bedrock.NoopBedrockService;
import com.qducks.superducksystem.integration.placeholder.SuperDuckExpansion;
import org.bukkit.Bukkit;

public final class IntegrationManager {
    private final SuperDuckSystem plugin;
    private BedrockService bedrockService = new NoopBedrockService();
    private boolean placeholderApi;
    private boolean luckPerms;
    private boolean vaultUnlocked;

    public IntegrationManager(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void detect() {
        placeholderApi = enabledByConfig("placeholderapi") && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        luckPerms = enabledByConfig("luckperms") && Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
        vaultUnlocked = enabledByConfig("vaultunlocked") && Bukkit.getPluginManager().isPluginEnabled("VaultUnlocked");

        if (enabledByConfig("floodgate") && Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            bedrockService = new FloodgateBedrockService();
        }

        if (placeholderApi) {
            new SuperDuckExpansion(plugin).register();
        }

        plugin.getLogger().info("Integrations: PlaceholderAPI=" + placeholderApi
                + ", Floodgate=" + bedrockService.available()
                + ", LuckPerms=" + luckPerms
                + ", VaultUnlocked=" + vaultUnlocked);
    }

    public BedrockService bedrock() {
        return bedrockService;
    }

    public boolean placeholderApi() {
        return placeholderApi;
    }

    public boolean luckPerms() {
        return luckPerms;
    }

    public boolean vaultUnlocked() {
        return vaultUnlocked;
    }

    private boolean enabledByConfig(String key) {
        return plugin.getConfig().getBoolean("integrations." + key, true);
    }
}
