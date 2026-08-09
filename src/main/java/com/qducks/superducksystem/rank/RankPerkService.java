package com.qducks.superducksystem.rank;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Converts configured LuckPerms rank perks into normal Bukkit permissions so every module can use
 * the same numeric permission system. Explicit higher numeric permissions still win naturally.
 */
public final class RankPerkService {
    private final SuperDuckSystem plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public RankPerkService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void apply(Player player) {
        clear(player);

        String group = plugin.integrations().ranks().primaryGroup(player).toLowerCase(Locale.ROOT);
        String base = "rank-perks." + group + ".";
        String fallback = "rank-perks.default.";

        int auctionSlots = Math.max(0, plugin.getConfig().getInt(base + "auction-slots",
                plugin.getConfig().getInt(fallback + "auction-slots", 3)));
        int orderSlots = Math.max(0, plugin.getConfig().getInt(base + "order-slots",
                plugin.getConfig().getInt(fallback + "order-slots", 3)));

        PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission("superduck.auction.slots." + auctionSlots, true);
        attachment.setPermission("superduck.order.slots." + orderSlots, true);
        attachments.put(player.getUniqueId(), attachment);
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
