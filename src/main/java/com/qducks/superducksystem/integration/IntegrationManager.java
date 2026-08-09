package com.qducks.superducksystem.integration;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.integration.bedrock.BedrockService;
import com.qducks.superducksystem.integration.bedrock.FloodgateBedrockService;
import com.qducks.superducksystem.integration.bedrock.NoopBedrockService;
import com.qducks.superducksystem.integration.placeholder.SuperDuckExpansion;
import com.qducks.superducksystem.integration.rank.LuckPermsRankService;
import com.qducks.superducksystem.integration.rank.NoopRankService;
import com.qducks.superducksystem.integration.rank.RankService;
import org.bukkit.Bukkit;

public final class IntegrationManager {
    private final SuperDuckSystem plugin;
    private BedrockService bedrockService = new NoopBedrockService();
    private RankService rankService = new NoopRankService();
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
            bedrockService = new FloodgateBedrockService(plugin);
        }

        if (luckPerms) {
            try {
                rankService = new LuckPermsRankService();
            } catch (RuntimeException exception) {
                luckPerms = false;
                plugin.getLogger().warning("LuckPerms was detected but its API service could not be loaded: " + exception.getMessage());
            }
        }

        if (placeholderApi) {
            new SuperDuckExpansion(plugin).register();
        }

        plugin.getLogger().info("Integrations: PlaceholderAPI=" + placeholderApi
                + ", Floodgate=" + bedrockService.available()
                + ", LuckPerms=" + rankService.available()
                + ", VaultUnlocked=" + vaultUnlocked);
    }

    public BedrockService bedrock() {
        return bedrockService;
    }

    public RankService ranks() {
        return rankService;
    }

    public boolean placeholderApi() {
        return placeholderApi;
    }

    public boolean luckPerms() {
        return rankService.available();
    }

    public boolean vaultUnlocked() {
        return vaultUnlocked;
    }

    private boolean enabledByConfig(String key) {
        return plugin.getConfig().getBoolean("integrations." + key, true);
    }
}
