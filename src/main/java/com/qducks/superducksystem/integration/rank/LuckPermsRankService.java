package com.qducks.superducksystem.integration.rank;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class LuckPermsRankService implements RankService {
    private final LuckPerms luckPerms;

    public LuckPermsRankService() {
        LuckPerms loaded = Bukkit.getServicesManager().load(LuckPerms.class);
        if (loaded == null) {
            throw new IllegalStateException("LuckPerms API service is unavailable");
        }
        this.luckPerms = loaded;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String primaryGroup(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return "default";
        }
        String group = user.getPrimaryGroup();
        return group == null || group.isBlank() ? "default" : group.toLowerCase();
    }
}
