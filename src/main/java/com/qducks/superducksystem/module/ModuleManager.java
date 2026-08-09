package com.qducks.superducksystem.module;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.EconomyModule;
import com.qducks.superducksystem.settings.SettingsModule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModuleManager {
    private final SuperDuckSystem plugin;
    private final Map<String, SuperDuckModule> modules = new LinkedHashMap<>();

    public ModuleManager(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void loadConfiguredModules() {
        register(new EconomyModule(plugin));
        register(new SettingsModule(plugin));
        plugin.getLogger().info("Module framework initialized with " + modules.size() + " registered module(s).");
    }

    public void register(SuperDuckModule module) {
        String id = module.id().toLowerCase();
        if (modules.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate module id: " + id);
        }
        modules.put(id, module);
        if (plugin.getConfig().getBoolean("modules." + id, false)) {
            module.enable();
        }
    }

    public Map<String, SuperDuckModule> all() {
        return Collections.unmodifiableMap(modules);
    }

    public void shutdown() {
        for (SuperDuckModule module : modules.values()) {
            try {
                module.disable();
            } catch (Exception exception) {
                plugin.getLogger().severe("Failed to disable module " + module.id() + ": " + exception.getMessage());
            }
        }
    }
}
