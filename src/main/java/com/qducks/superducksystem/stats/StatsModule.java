package com.qducks.superducksystem.stats;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class StatsModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;

    public StatsModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "stats";
    }

    @Override
    public void enable() {
        plugin.stats().start();
        plugin.getServer().getPluginManager().registerEvents(new StatsListener(plugin.stats()), plugin);

        PluginCommand stats = plugin.getCommand("stats");
        if (stats == null) throw new IllegalStateException("Command /stats is missing from plugin.yml");
        stats.setExecutor(new StatsCommand(plugin));

        PluginCommand leaderboard = plugin.getCommand("leaderboard");
        if (leaderboard == null) throw new IllegalStateException("Command /leaderboard is missing from plugin.yml");
        LeaderboardCommand handler = new LeaderboardCommand(plugin);
        leaderboard.setExecutor(handler);
        leaderboard.setTabCompleter(handler);
        plugin.getLogger().info("Stats/leaderboards module enabled.");
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Stats/leaderboards module disabled.");
    }
}
