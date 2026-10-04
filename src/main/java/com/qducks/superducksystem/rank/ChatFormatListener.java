package com.qducks.superducksystem.rank;

import com.qducks.superducksystem.SuperDuckSystem;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SuperDuckSystem plugin;

    public ChatFormatListener(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat-format.enabled", true)) return;

        Player player = event.getPlayer();
        String format = plugin.getConfig().getString("chat-format.format", "%prefix%%name%%suffix% &8» &f%message%");
        // Prefixes/suffixes are trusted (LuckPerms) and may use &-codes or MiniMessage (e.g. <gradient>).
        Component prefix = rankText(plugin.integrations().ranks().prefix(player));
        Component suffix = rankText(plugin.integrations().ranks().suffix(player));

        Component template = LEGACY.deserialize(format.replace("%name%", player.getName()));
        Component base = replace(replace(template, "%prefix%", prefix), "%suffix%", suffix);

        event.renderer((source, displayName, message, viewer) -> replace(base, "%message%", message));
    }

    private static Component replace(Component component, String placeholder, Component value) {
        return component.replaceText(TextReplacementConfig.builder().matchLiteral(placeholder).replacement(value).build());
    }

    private static Component rankText(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        // MiniMessage tags look like <tag>; otherwise treat it as legacy &-codes.
        return raw.matches(".*<[a-zA-Z#/!][^>]*>.*") ? MINI.deserialize(raw) : LEGACY.deserialize(raw);
    }
}
