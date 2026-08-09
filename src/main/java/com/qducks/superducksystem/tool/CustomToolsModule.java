package com.qducks.superducksystem.tool;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;

public final class CustomToolsModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;

    public CustomToolsModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "custom-tools";
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(new DuckToolListener(plugin), plugin);
        plugin.getLogger().info("Duck custom tools module enabled.");
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Duck custom tools module disabled.");
    }
}
