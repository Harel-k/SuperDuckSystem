package com.qducks.superducksystem;

import com.qducks.superducksystem.command.SuperDuckCommand;
import com.qducks.superducksystem.config.ConfigManager;
import com.qducks.superducksystem.database.DatabaseManager;
import com.qducks.superducksystem.economy.EconomyService;
import com.qducks.superducksystem.gui.GuiManager;
import com.qducks.superducksystem.integration.IntegrationManager;
import com.qducks.superducksystem.item.CustomItemService;
import com.qducks.superducksystem.module.ModuleManager;
import com.qducks.superducksystem.player.PlayerProfileListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SuperDuckSystem extends JavaPlugin {
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private EconomyService economyService;
    private IntegrationManager integrationManager;
    private ModuleManager moduleManager;
    private CustomItemService customItemService;
    private GuiManager guiManager;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.start();

        this.economyService = new EconomyService(this);
        this.customItemService = new CustomItemService(this);
        this.guiManager = new GuiManager(this);

        this.integrationManager = new IntegrationManager(this);
        this.integrationManager.detect();

        this.moduleManager = new ModuleManager(this);
        this.moduleManager.loadConfiguredModules();

        getServer().getPluginManager().registerEvents(new PlayerProfileListener(this), this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        PluginCommand command = getCommand("superduck");
        if (command == null) {
            throw new IllegalStateException("Command /superduck is missing from plugin.yml");
        }
        SuperDuckCommand handler = new SuperDuckCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        getLogger().info("SuperDuckSystem " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    public ConfigManager configs() {
        return configManager;
    }

    public DatabaseManager database() {
        return databaseManager;
    }

    public EconomyService economy() {
        return economyService;
    }

    public IntegrationManager integrations() {
        return integrationManager;
    }

    public ModuleManager modules() {
        return moduleManager;
    }

    public CustomItemService customItems() {
        return customItemService;
    }

    public GuiManager guis() {
        return guiManager;
    }
}
