package com.qducks.superducksystem.order;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class OrderModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final OrderService service;

    public OrderModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.service = new OrderService(plugin);
    }

    @Override
    public String id() {
        return "orders";
    }

    @Override
    public void enable() {
        PluginCommand command = plugin.getCommand("order");
        if (command == null) {
            throw new IllegalStateException("Command /order is missing from plugin.yml");
        }
        command.setExecutor(new OrderCommand(plugin, service));
        plugin.getLogger().info("Buy Orders module enabled.");
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Buy Orders module disabled.");
    }
}
