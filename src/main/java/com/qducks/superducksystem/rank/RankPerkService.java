package com.qducks.superducksystem.rank;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Converts configured LuckPerms rank perks into normal Bukkit permissions so every module can use
 * the same numeric permission system. Rank config IDs are intentionally separate from LuckPerms
 * group names so server owners can rename groups without recompiling SuperDuckSystem.
 */
public final class RankPerkService {
    private final SuperDuckSystem plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public RankPerkService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void apply(Player player) {
        clear(player);

        String primaryGroup = plugin.integrations().ranks().primaryGroup(player).toLowerCase(Locale.ROOT);
        ConfigurationSection fallback = plugin.getConfig().getConfigurationSection("rank-perks.default");
        ConfigurationSection rank = findRank(primaryGroup);

        int defaultAuctionSlots = getNonNegative(fallback, "auction-slots", 18);
        int defaultOrderSlots = getNonNegative(fallback, "order-slots", 18);
        int auctionSlots = getNonNegative(rank, "auction-slots", defaultAuctionSlots);
        int orderSlots = getNonNegative(rank, "order-slots", defaultOrderSlots);

        PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission("superduck.auction.slots." + auctionSlots, true);
        attachment.setPermission("superduck.order.slots." + orderSlots, true);
        applyExtraPermissions(attachment, fallback);
        applyExtraPermissions(attachment, rank);
        attachments.put(player.getUniqueId(), attachment);
    }

    private ConfigurationSection findRank(String primaryGroup) {
        ConfigurationSection ranks = plugin.getConfig().getConfigurationSection("rank-perks.ranks");
        if (ranks != null) {
            for (String rankId : ranks.getKeys(false)) {
                ConfigurationSection section = ranks.getConfigurationSection(rankId);
                if (section == null || !section.getBoolean("enabled", true)) {
                    continue;
                }

                String configuredGroup = section.getString("group-name", rankId);
                if (configuredGroup != null && configuredGroup.equalsIgnoreCase(primaryGroup)) {
                    return section;
                }

                for (String alias : section.getStringList("group-aliases")) {
                    if (alias.equalsIgnoreCase(primaryGroup)) {
                        return section;
                    }
                }
            }
        }

        // Backward compatibility for older configs where the section name itself was the group name.
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("rank-perks." + primaryGroup);
        if (legacy != null && !primaryGroup.equals("default") && !primaryGroup.equals("ranks")) {
            return legacy;
        }
        return null;
    }

    private int getNonNegative(ConfigurationSection section, String path, int fallback) {
        if (section == null) {
            return Math.max(0, fallback);
        }
        return Math.max(0, section.getInt(path, fallback));
    }

    private void applyExtraPermissions(PermissionAttachment attachment, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        List<String> permissions = section.getStringList("permissions");
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                continue;
            }
            attachment.setPermission(permission.trim(), true);
        }
    }

    public void clear(Player player) {
        PermissionAttachment previous = attachments.remove(player.getUniqueId());
        if (previous != null) {
            player.removeAttachment(previous);
        }
    }

    public void reloadOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            apply(player);
        }
    }

    public void shutdown() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clear(player);
        }
        attachments.clear();
    }
}
