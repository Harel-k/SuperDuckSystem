package com.qducks.superducksystem;

import com.qducks.superducksystem.command.SuperDuckCommand;
import com.qducks.superducksystem.config.ConfigManager;
import com.qducks.superducksystem.database.DatabaseManager;
import com.qducks.superducksystem.economy.EconomyService;
import com.qducks.superducksystem.gui.GuiManager;
import com.qducks.superducksystem.input.VirtualSignInputService;
import com.qducks.superducksystem.integration.IntegrationManager;
import com.qducks.superducksystem.item.CustomItemService;
import com.qducks.superducksystem.module.ModuleManager;
import com.qducks.superducksystem.player.PlayerProfileListener;
import com.qducks.superducksystem.rank.RankPerkListener;
import com.qducks.superducksystem.rank.RankPerkService;
import com.qducks.superducksystem.settings.SettingsService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SuperDuckSystem extends JavaPlugin {
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private EconomyService economyService;
    private SettingsService settingsService;
    private IntegrationManager integrationManager;
    private ModuleManager moduleManager;
    private CustomItemService customItemService;
    private GuiManager guiManager;
    private VirtualSignInputService signInputService;
    private RankPerkService rankPerkService;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.start();

        this.economyService = new EconomyService(this);
        this.settingsService = new SettingsService(this);
        this.customItemService = new CustomItemService(this);
        this.guiManager = new GuiManager(this);
        this.signInputService = new VirtualSignInputService(this);

        this.integrationManager = new IntegrationManager(this);
        this.integrationManager.detect();
        this.rankPerkService = new RankPerkService(this);

        this.moduleManager = new ModuleManager(this);
        this.moduleManager.loadConfiguredModules();

        getServer().getPluginManager().registerEvents(new PlayerProfileListener(this), this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        getServer().getPluginManager().registerEvents(signInputService, this);
        getServer().getPluginManager().registerEvents(new RankPerkListener(rankPerkService), this);
        rankPerkService.reloadOnlinePlayers();

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
        if (rankPerkService != null) {
            rankPerkService.shutdown();
        }
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

    public SettingsService settings() {
        return settingsService;
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

    public VirtualSignInputService signInput() {
        return signInputService;
    }

    public RankPerkService rankPerks() {
        return rankPerkService;
    }
}
