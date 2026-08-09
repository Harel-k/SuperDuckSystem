package com.qducks.superducksystem.reward;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class RewardsCommand implements CommandExecutor {
    private final SuperDuckSystem plugin;
    private final RewardsMenu menu;

    public RewardsCommand(SuperDuckSystem plugin, RewardService rewards) {
        this.plugin = plugin;
        this.menu = new RewardsMenu(plugin, rewards);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>This command can only be used by players.</red>");
            return true;
        }
        if (plugin.state().maintenance("rewards")) {
            player.sendRichMessage("<red>Rewards are temporarily in maintenance mode.</red>");
            return true;
        }
        if (args.length != 0) {
            player.sendRichMessage("<red>Usage: /rewards</red>");
            return true;
        }
        menu.open(player);
        return true;
    }
}
