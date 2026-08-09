package com.qducks.superducksystem.integration.rank;

import org.bukkit.entity.Player;

public final class NoopRankService implements RankService {
    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String primaryGroup(Player player) {
        return "default";
    }
}
