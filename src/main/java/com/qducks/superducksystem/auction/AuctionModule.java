package com.qducks.superducksystem.auction;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class AuctionModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final AuctionService service;

    public AuctionModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.service = new AuctionService(plugin);
    }

    @Override
    public String id() {
        return "auctions";
    }

    @Override
    public void enable() {
        PluginCommand command = plugin.getCommand("auctionhouse");
        if (command == null) {
            throw new IllegalStateException("Command /ah is missing from plugin.yml");
        }
        command.setExecutor(new AuctionCommand(plugin, service));
        plugin.getLogger().info("Auction House module enabled.");
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Auction House module disabled.");
    }
}
