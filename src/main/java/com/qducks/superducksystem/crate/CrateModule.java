package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class CrateModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final KeyService keyService;
    private final CrateService crateService;
    private final CrateLocationService locationService;

    public CrateModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.keyService = new KeyService(plugin);
        this.crateService = new CrateService(plugin, keyService);
        this.locationService = new CrateLocationService(plugin, crateService, keyService);
    }

    @Override
    public String id() {
        return "crates";
    }

    @Override
    public void enable() {
        PluginCommand keyCommand = plugin.getCommand("key");
        if (keyCommand == null) {
            throw new IllegalStateException("Command /key is missing from plugin.yml");
        }
        keyCommand.setExecutor(new KeyCommand(plugin, keyService, crateService));

        PluginCommand crateCommand = plugin.getCommand("crates");
        if (crateCommand == null) {
            throw new IllegalStateException("Command /crates is missing from plugin.yml");
        }
        crateCommand.setExecutor(new CrateCommand(plugin, keyService, crateService));

        plugin.getServer().getPluginManager().registerEvents(keyService, plugin);
        plugin.getServer().getPluginManager().registerEvents(locationService, plugin);
        keyService.start();
        locationService.start();
        plugin.getLogger().info("Crates/key progression/physical crate module enabled.");
    }

    @Override
    public void reload() {
        locationService.reload();
    }

    @Override
    public void disable() {
        locationService.stop();
        keyService.stop();
        plugin.getLogger().info("Crates/key progression module disabled.");
    }
}
