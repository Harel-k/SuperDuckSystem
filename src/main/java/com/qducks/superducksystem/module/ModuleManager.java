package com.qducks.superducksystem.module;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.adminmode.AdminModeModule;
import com.qducks.superducksystem.auction.AuctionModule;
import com.qducks.superducksystem.combat.CombatModule;
import com.qducks.superducksystem.crate.CrateModule;
import com.qducks.superducksystem.economy.EconomyModule;
import com.qducks.superducksystem.maintenance.MaintenanceModule;
import com.qducks.superducksystem.order.OrderModule;
import com.qducks.superducksystem.reward.RewardsModule;
import com.qducks.superducksystem.rtp.RtpModule;
import com.qducks.superducksystem.settings.SettingsModule;
import com.qducks.superducksystem.shop.ShopModule;
import com.qducks.superducksystem.stats.StatsModule;
import com.qducks.superducksystem.tool.CustomToolsModule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ModuleManager {
    private final SuperDuckSystem plugin;
    private final Map<String, SuperDuckModule> modules = new LinkedHashMap<>();
    private final Set<String> enabledModules = new LinkedHashSet<>();

    public ModuleManager(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void loadConfiguredModules() {
        register(new EconomyModule(plugin));
        register(new SettingsModule(plugin));
        register(new ShopModule(plugin));
        register(new AuctionModule(plugin));
        register(new OrderModule(plugin));
        register(new CrateModule(plugin, plugin.keys()));
        register(new CustomToolsModule(plugin));
        register(new StatsModule(plugin));
        register(new RewardsModule(plugin, plugin.rewards()));
        register(new RtpModule(plugin));
        register(new CombatModule(plugin));
        register(new MaintenanceModule(plugin));
        register(new AdminModeModule(plugin));
        plugin.getLogger().info("Module framework initialized with " + modules.size() + " registered module(s).");
    }

    public void register(SuperDuckModule module) {
        String id = module.id().toLowerCase();
        if (modules.containsKey(id)) throw new IllegalArgumentException("Duplicate module id: " + id);
        modules.put(id, module);
        if (configuredEnabled(id)) {
            module.enable();
            enabledModules.add(id);
        }
    }

    public Map<String, SuperDuckModule> all() { return Collections.unmodifiableMap(modules); }

    public void reload() {
        for (Map.Entry<String, SuperDuckModule> entry : modules.entrySet()) {
            String id = entry.getKey();
            SuperDuckModule module = entry.getValue();
            boolean shouldBeEnabled = configuredEnabled(id);
            boolean isEnabled = enabledModules.contains(id);

            try {
                if (shouldBeEnabled && !isEnabled) {
                    module.enable();
                    enabledModules.add(id);
                    plugin.getLogger().info("Module " + module.id() + " enabled after configuration reload.");
                } else if (!shouldBeEnabled && isEnabled) {
                    module.disable();
                    enabledModules.remove(id);
                    plugin.getLogger().info("Module " + module.id() + " disabled after configuration reload.");
                } else if (shouldBeEnabled) {
                    module.reload();
                }
            } catch (Exception exception) {
                plugin.getLogger().severe("Failed to reload module " + module.id() + ": " + exception.getMessage());
            }
        }
    }

    public void shutdown() {
        for (Map.Entry<String, SuperDuckModule> entry : modules.entrySet()) {
            if (!enabledModules.contains(entry.getKey())) continue;
            try {
                entry.getValue().disable();
            } catch (Exception exception) {
                plugin.getLogger().severe("Failed to disable module " + entry.getValue().id() + ": " + exception.getMessage());
            }
        }
        enabledModules.clear();
    }

    private boolean configuredEnabled(String id) {
        boolean defaultEnabled = id.equals("maintenance") || id.equals("abuse") || id.equals("rtp");
        return plugin.getConfig().getBoolean("modules." + id, defaultEnabled);
    }
}
