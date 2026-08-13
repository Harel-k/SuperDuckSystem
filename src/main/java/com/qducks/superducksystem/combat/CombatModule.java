package com.qducks.superducksystem.combat;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.module.SuperDuckModule;

public final class CombatModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;
    private final CombatService combat;
    private CombatListener listener;

    public CombatModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.combat = new CombatService(plugin);
    }

    @Override
    public String id() {
        return "combat";
    }

    @Override
    public void enable() {
        if (listener != null) return;
        listener = new CombatListener(combat);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        combat.start();
        plugin.getLogger().info("Combat module enabled.");
    }

    @Override
    public void reload() {
        combat.reload();
    }

    @Override
    public void disable() {
        combat.stop();
        if (listener != null) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            listener = null;
        }
    }
}
