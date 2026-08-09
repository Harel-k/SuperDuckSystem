package com.qducks.superducksystem.integration.rank;

import org.bukkit.entity.Player;

public interface RankService {
    boolean available();

    String primaryGroup(Player player);
}
