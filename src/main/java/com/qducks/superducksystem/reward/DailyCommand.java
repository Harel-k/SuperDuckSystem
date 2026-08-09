package com.qducks.superducksystem.reward;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class DailyCommand implements CommandExecutor {
    private final SuperDuckSystem plugin;
    private final RewardService rewards;

    public DailyCommand(SuperDuckSystem plugin, RewardService rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>This command can only be used by players.</red>");
            return true;
        }
        if (args.length != 0) {
            player.sendRichMessage("<red>Usage: /daily</red>");
            return true;
        }
        rewards.claimDaily(player).whenComplete((claim, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof RewardService.DailyCooldownException cooldown) {
                    player.sendRichMessage("<yellow>Your next daily reward is available in <white>"
                            + RewardService.formatDuration(cooldown.remainingMillis()) + "</white>.</yellow>");
                } else {
                    player.sendRichMessage("<red>Could not claim your daily reward: <white>" + escape(message(cause)) + "</white></red>");
                }
                return;
            }
            player.sendRichMessage("<green><bold>Daily Reward Claimed!</bold></green> <gray>Streak:</gray> <white>" + claim.streak() + "</white>");
            for (String reward : claim.rewards()) {
                player.sendRichMessage("<dark_gray>•</dark_gray> <yellow>" + escape(reward) + "</yellow>");
            }
        }));
        return true;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<");
    }
}
