package com.qducks.superducksystem.shop;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class ShopModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final ShopService shop;

    public ShopModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.shop = new ShopService(plugin);
    }

    @Override
    public String id() {
        return "shop";
    }

    @Override
    public void enable() {
        PluginCommand sell = plugin.getCommand("sell");
        if (sell == null) {
            throw new IllegalStateException("Command /sell is missing from plugin.yml");
        }
        sell.setExecutor(new SellCommand(plugin, shop));
        plugin.getLogger().info("Shop module enabled with /sell.");
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Shop module disabled.");
    }
}
