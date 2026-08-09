package com.qducks.superducksystem.reward;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class RewardsModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final RewardService rewards;

    public RewardsModule(SuperDuckSystem plugin, RewardService rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
    }

    @Override
    public String id() {
        return "rewards";
    }

    @Override
    public void enable() {
        rewards.start();
        PluginCommand daily = plugin.getCommand("daily");
        if (daily == null) throw new IllegalStateException("Command /daily is missing from plugin.yml");
        daily.setExecutor(new DailyCommand(plugin, rewards));
        PluginCommand rewardsCommand = plugin.getCommand("rewards");
        if (rewardsCommand == null) throw new IllegalStateException("Command /rewards is missing from plugin.yml");
        rewardsCommand.setExecutor(new RewardsCommand(plugin, rewards));
        plugin.getLogger().info("Daily/playtime rewards module enabled.");
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Daily/playtime rewards module disabled.");
    }
}
