package com.qducks.superducksystem.integration.rank;

import org.bukkit.entity.Player;

public interface RankService {
    boolean available();

    String primaryGroup(Player player);

    /** LuckPerms chat prefix (legacy &-codes), or empty. */
    default String prefix(Player player) { return ""; }

    /** LuckPerms chat suffix (legacy &-codes), or empty. */
    default String suffix(Player player) { return ""; }
}
