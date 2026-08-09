package com.qducks.superducksystem.reward;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.gui.DuckGui;
import com.qducks.superducksystem.gui.GuiButton;
import com.qducks.superducksystem.gui.GuiItems;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

public final class RewardsMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final SuperDuckSystem plugin;
    private final RewardService rewards;

    public RewardsMenu(SuperDuckSystem plugin, RewardService rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
    }

    public void open(Player player) {
        rewards.playtimeRewards(player.getUniqueId()).whenComplete((entries, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null) {
                        player.sendRichMessage("<red>Could not load playtime rewards right now.</red>");
                        return;
                    }
                    render(player, entries);
                }));
    }

    private void render(Player player, List<RewardService.PlaytimeReward> entries) {
        FileConfiguration config = plugin.configs().rewards();
        int rows = Math.max(1, Math.min(6, config.getInt("playtime.menu.rows", 6)));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("playtime.menu.title", "<aqua><bold>Playtime Rewards</bold></aqua>")));
        fill(gui, config);

        for (RewardService.PlaytimeReward entry : entries) {
            String base = "playtime.milestones." + entry.id();
            int slot = config.getInt(base + ".slot", -1);
            if (slot < 0 || slot >= gui.getInventory().getSize()) continue;
            Material material = Material.matchMaterial(config.getString(base + ".icon", "CHEST"));
            if (material == null) material = Material.CHEST;
            String name = config.getString(base + ".name", "<yellow>" + pretty(entry.id()) + "</yellow>");
            String status;
            if (entry.claimed()) status = "<green>CLAIMED</green>";
            else if (entry.unlocked()) status = "<yellow>CLICK TO CLAIM</yellow>";
            else status = "<red>LOCKED</red>";
            ItemStack icon = GuiItems.item(material, name,
                    "<gray>Required playtime:</gray> <white>" + formatSeconds(entry.requiredSeconds()) + "</white>",
                    "",
                    status);
            if (entry.unlocked() && !entry.claimed()) {
                gui.set(slot, new GuiButton(icon, context -> claim(context.player(), entry.id())));
            } else {
                gui.setDisplay(slot, icon);
            }
        }

        int closeSlot = config.getInt("playtime.menu.close.slot", 49);
        if (closeSlot >= 0 && closeSlot < gui.getInventory().getSize()) {
            Material material = Material.matchMaterial(config.getString("playtime.menu.close.material", "BARRIER"));
            if (material == null) material = Material.BARRIER;
            gui.set(closeSlot, new GuiButton(
                    GuiItems.item(material, config.getString("playtime.menu.close.name", "<red>Close</red>")),
                    context -> context.player().closeInventory()));
        }
        gui.open(player);
    }

    private void claim(Player player, String rewardId) {
        rewards.claimPlaytime(player, rewardId).whenComplete((descriptions, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        if (cause instanceof RewardService.AlreadyClaimedException) {
                            player.sendRichMessage("<yellow>You already claimed that reward.</yellow>");
                        } else if (cause instanceof RewardService.NotEnoughPlaytimeException notEnough) {
                            player.sendRichMessage("<yellow>You need <white>" + formatSeconds(notEnough.remainingSeconds()) + "</white> more playtime.</yellow>");
                        } else {
                            player.sendRichMessage("<red>Could not claim that reward.</red>");
                        }
                        open(player);
                        return;
                    }
                    player.sendRichMessage("<green><bold>Playtime Reward Claimed!</bold></green>");
                    for (String description : descriptions) {
                        player.sendRichMessage("<dark_gray>•</dark_gray> <yellow>" + escape(description) + "</yellow>");
                    }
                    open(player);
                }));
    }

    private void fill(DuckGui gui, FileConfiguration config) {
        if (!config.getBoolean("playtime.menu.filler.enabled", true)) return;
        Material material = Material.matchMaterial(config.getString("playtime.menu.filler.material", "BLACK_STAINED_GLASS_PANE"));
        if (material == null) material = Material.BLACK_STAINED_GLASS_PANE;
        ItemStack filler = GuiItems.item(material, config.getString("playtime.menu.filler.name", " "));
        for (int slot = 0; slot < gui.getInventory().getSize(); slot++) gui.setDisplay(slot, filler);
    }

    private String formatSeconds(long seconds) {
        long hours = Math.max(0, seconds) / 3600L;
        long minutes = (Math.max(0, seconds) % 3600L) / 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private String pretty(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<");
    }
}
