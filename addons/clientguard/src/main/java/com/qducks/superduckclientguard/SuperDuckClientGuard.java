package com.qducks.superduckclientguard;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class SuperDuckClientGuard extends JavaPlugin {
    private SuperDuckSystem superDuckSystem;
    private StrikeStore strikeStore;
    private EnforcementService enforcementService;
    private ClientScanService scanService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Plugin dependency = getServer().getPluginManager().getPlugin("SuperDuckSystem");
        if (!(dependency instanceof SuperDuckSystem sds) || !dependency.isEnabled()) {
            getLogger().severe("SuperDuckSystem is required and must be enabled first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.superDuckSystem = sds;

        this.strikeStore = new StrikeStore(this);
        this.enforcementService = new EnforcementService(this, strikeStore);
        this.scanService = new ClientScanService(this, enforcementService);

        ClientGuardListener listener = new ClientGuardListener(this, scanService);
        getServer().getPluginManager().registerEvents(listener, this);

        PluginCommand command = getCommand("clientguard");
        if (command == null) {
            throw new IllegalStateException("Command /clientguard is missing from plugin.yml");
        }
        ClientGuardCommand commandHandler = new ClientGuardCommand(this);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        getLogger().info("SuperDuckClientGuard " + getPluginMeta().getVersion()
                + " enabled. Auto-enforcement=" + getConfig().getBoolean("enforcement.enabled", false));
    }

    @Override
    public void onDisable() {
        if (scanService != null) scanService.shutdown();
    }

    SuperDuckSystem superDuckSystem() { return superDuckSystem; }
    StrikeStore strikes() { return strikeStore; }
    EnforcementService enforcement() { return enforcementService; }
    ClientScanService scans() { return scanService; }

    void reloadAddonConfig() {
        reloadConfig();
        if (scanService != null) scanService.reloadDefinitions();
    }
}
