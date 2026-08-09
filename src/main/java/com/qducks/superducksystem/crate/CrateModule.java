package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class CrateModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final KeyService keyService;

    public CrateModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.keyService = new KeyService(plugin);
    }

    @Override
    public String id() {
        return "crates";
    }

    @Override
    public void enable() {
        PluginCommand command = plugin.getCommand("key");
        if (command == null) {
            throw new IllegalStateException("Command /key is missing from plugin.yml");
        }
        command.setExecutor(new KeyCommand(plugin, keyService));
        plugin.getServer().getPluginManager().registerEvents(keyService, plugin);
        keyService.start();
        plugin.getLogger().info("Crates/key progression module enabled.");
    }

    @Override
    public void disable() {
        keyService.stop();
        plugin.getLogger().info("Crates/key progression module disabled.");
    }
}
