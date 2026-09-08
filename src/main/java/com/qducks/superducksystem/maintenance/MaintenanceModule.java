package com.qducks.superducksystem.maintenance;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;

public final class MaintenanceModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final MaintenanceService maintenance;
    private MaintenanceListener listener;

    public MaintenanceModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.maintenance = new MaintenanceService(plugin);
    }

    @Override
    public String id() {
        return "maintenance";
    }

    @Override
    public void enable() {
        if (listener != null) return;

        PluginCommand command = plugin.getCommand("maintenancemode");
        if (command == null) {
            throw new IllegalStateException("Command /maintenancemode is missing from plugin.yml");
        }

        MaintenanceCommand handler = new MaintenanceCommand(maintenance);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        listener = new MaintenanceListener(maintenance);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        maintenance.start();
        plugin.getLogger().info("Full server maintenance module enabled.");
    }

    @Override
    public void reload() {
        maintenance.reload();
        maintenance.reconcileOnlinePlayers();
    }

    @Override
    public void disable() {
        if (listener != null) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            listener = null;
        }
        plugin.getLogger().info("Full server maintenance module disabled.");
    }
}
