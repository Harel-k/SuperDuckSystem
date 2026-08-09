package com.qducks.superducksystem.settings;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class SettingsModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;

    public SettingsModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public void enable() {
        PluginCommand command = plugin.getCommand("settings");
        if (command == null) {
            throw new IllegalStateException("Command /settings is missing from plugin.yml");
        }
        command.setExecutor(new SettingsCommand(plugin));
        plugin.getLogger().info("Settings module enabled.");
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Settings module disabled.");
    }
}
