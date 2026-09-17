package com.qducks.superducksystem.rtp;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;

public final class RtpModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final RtpService service;
    private RtpListener listener;

    public RtpModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.service = new RtpService(plugin);
    }

    @Override
    public String id() {
        return "rtp";
    }

    @Override
    public void enable() {
        if (listener != null) return;

        PluginCommand command = plugin.getCommand("rtp");
        if (command == null) throw new IllegalStateException("Command /rtp is missing from plugin.yml");

        service.start();
        RtpCommand handler = new RtpCommand(service);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        listener = new RtpListener(service);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getLogger().info("RTP module enabled.");
    }

    @Override
    public void reload() {
        service.reload();
    }

    @Override
    public void disable() {
        service.shutdown();
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        plugin.getLogger().info("RTP module disabled.");
    }
}
