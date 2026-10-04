package com.qducks.superducksystem.rank;

import com.qducks.superducksystem.SuperDuckSystem;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Formats chat with the player's LuckPerms rank prefix/suffix (the same ones TAB shows), e.g.
 * "ADMIN NAVIDOG100 » hello". The message itself is kept as the player typed it, so players
 * can't use color codes.
 */
public final class ChatFormatListener implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final SuperDuckSystem plugin;

    public ChatFormatListener(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat-format.enabled", true)) return;

        Player player = event.getPlayer();
        String format = plugin.getConfig().getString("chat-format.format", "%prefix%%name%%suffix% &8» &f%message%");
        String prefix = plugin.integrations().ranks().prefix(player);
        String suffix = plugin.integrations().ranks().suffix(player);

        // Everything except %message% is trusted (from config/LuckPerms) and may use &-codes.
        Component template = LEGACY.deserialize(format
                .replace("%prefix%", prefix)
                .replace("%suffix%", suffix)
                .replace("%name%", player.getName()));

        event.renderer((source, displayName, message, viewer) -> template.replaceText(TextReplacementConfig.builder()
                .matchLiteral("%message%")
                .replacement(message)
                .build()));
    }
}
