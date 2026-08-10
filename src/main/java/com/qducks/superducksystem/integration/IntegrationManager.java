package com.qducks.superducksystem.integration;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.integration.bedrock.BedrockService;
import com.qducks.superducksystem.integration.bedrock.FloodgateBedrockService;
import com.qducks.superducksystem.integration.bedrock.NoopBedrockService;
import com.qducks.superducksystem.integration.placeholder.SuperDuckExpansion;
import com.qducks.superducksystem.integration.rank.LuckPermsRankService;
import com.qducks.superducksystem.integration.rank.NoopRankService;
import com.qducks.superducksystem.integration.rank.RankService;
import com.qducks.superducksystem.integration.vault.SuperDuckVaultEconomy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

public final class IntegrationManager {
    private final SuperDuckSystem plugin;
    private BedrockService bedrockService = new NoopBedrockService();
    private RankService rankService = new NoopRankService();
    private boolean placeholderApi;
    private boolean luckPerms;
    private boolean vaultUnlocked;
    private SuperDuckVaultEconomy vaultEconomy;

    public IntegrationManager(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void detect() {
        placeholderApi = enabledByConfig("placeholderapi") && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        luckPerms = enabledByConfig("luckperms") && Bukkit.getPluginManager().isPluginEnabled("LuckPerms");

        // VaultUnlocked intentionally keeps the classic Bukkit plugin name "Vault"
        // for compatibility. Also accept "VaultUnlocked" in case a downstream build
        // changes the descriptor name.
        boolean vaultBridgePresent = Bukkit.getPluginManager().isPluginEnabled("Vault")
                || Bukkit.getPluginManager().isPluginEnabled("VaultUnlocked");
        vaultUnlocked = enabledByConfig("vaultunlocked") && vaultBridgePresent;

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

        if (vaultUnlocked) {
            try {
                vaultEconomy = new SuperDuckVaultEconomy(plugin);
                Bukkit.getServicesManager().register(Economy.class, vaultEconomy, plugin, ServicePriority.Highest);
                plugin.getLogger().info("Registered SuperDuckSystem as the Vault/VaultUnlocked economy provider.");
            } catch (LinkageError | RuntimeException exception) {
                vaultUnlocked = false;
                vaultEconomy = null;
                plugin.getLogger().warning("VaultUnlocked was detected but the economy provider could not be registered: " + exception.getMessage());
            }
        }

        plugin.getLogger().info("Integrations: PlaceholderAPI=" + placeholderApi
                + ", Floodgate=" + bedrockService.available()
                + ", LuckPerms=" + rankService.available()
                + ", VaultUnlocked=" + vaultUnlocked);
    }

    public void shutdown() {
        if (vaultEconomy != null) {
            try {
                Bukkit.getServicesManager().unregister(Economy.class, vaultEconomy);
            } catch (RuntimeException ignored) {
            }
            vaultEconomy = null;
        }
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
