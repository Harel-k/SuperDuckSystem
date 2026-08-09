package com.qducks.superducksystem.message;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.Map;

public final class MessageService {
    private final SuperDuckSystem plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public MessageService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void send(CommandSender sender, String path, String fallback) {
        sender.sendMessage(component(path, fallback, Map.of()));
    }

    public void send(CommandSender sender, String path, String fallback, Map<String, String> placeholders) {
        sender.sendMessage(component(path, fallback, placeholders));
    }

    public Component component(String path, String fallback, Map<String, String> placeholders) {
        String raw = plugin.configs().messages().getString(path, fallback);
        raw = raw.replace("%server_name%", safe(plugin.configs().serverName()))
                .replace("%version%", safe(plugin.getPluginMeta().getVersion()));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace("%" + entry.getKey() + "%", safe(entry.getValue()));
        }
        return mini.deserialize(raw);
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("<", "\\<");
    }
}
