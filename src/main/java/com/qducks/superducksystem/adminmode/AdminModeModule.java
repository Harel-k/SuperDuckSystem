package com.qducks.superducksystem.adminmode;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;

public final class AdminModeModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final AdminModeService service;
    private AdminModeListener listener;

    public AdminModeModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.service = new AdminModeService(plugin);
    }

    @Override
    public String id() {
        return "abuse";
    }

    @Override
    public void enable() {
        if (listener != null) return;
        PluginCommand command = plugin.getCommand("abuse");
        if (command == null) throw new IllegalStateException("Command /abuse is missing from plugin.yml");
        AdminModeCommand handler = new AdminModeCommand(service);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        listener = new AdminModeListener(service);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        service.start();
        plugin.getLogger().info("Abuse Mode module enabled.");
    }

    @Override
    public void reload() {
        service.reload();
    }

    @Override
    public void disable() {
        service.stop();
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        plugin.getLogger().info("Abuse Mode module disabled.");
    }
}
