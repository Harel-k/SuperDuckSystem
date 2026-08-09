package com.qducks.superducksystem.config;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public final class ConfigManager {
    private final SuperDuckSystem plugin;
    private FileConfiguration messages;
    private FileConfiguration economy;
    private FileConfiguration gui;
    private FileConfiguration settings;
    private FileConfiguration shop;
    private FileConfiguration auctions;
    private FileConfiguration orders;
    private FileConfiguration crates;

    public ConfigManager(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("economy.yml");
        saveResourceIfMissing("gui.yml");
        saveResourceIfMissing("settings.yml");
        saveResourceIfMissing("shop.yml");
        saveResourceIfMissing("auctions.yml");
        saveResourceIfMissing("orders.yml");
        saveResourceIfMissing("crates.yml");
        reloadSecondaryFiles();
    }

    public void reload() {
        plugin.reloadConfig();
        reloadSecondaryFiles();
    }

    public FileConfiguration main() {
        return plugin.getConfig();
    }

    public FileConfiguration messages() {
        return messages;
    }

    public FileConfiguration economy() {
        return economy;
    }

    public FileConfiguration gui() {
        return gui;
    }

    public FileConfiguration settings() {
        return settings;
    }

    public FileConfiguration shop() {
        return shop;
    }

    public FileConfiguration auctions() {
        return auctions;
    }

    public FileConfiguration orders() {
        return orders;
    }

    public FileConfiguration crates() {
        return crates;
    }

    public String serverName() {
        return main().getString("server.name", "Server");
    }

    private void reloadSecondaryFiles() {
        messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        economy = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "economy.yml"));
        gui = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "gui.yml"));
        settings = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "settings.yml"));
        shop = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "shop.yml"));
        auctions = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "auctions.yml"));
        orders = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "orders.yml"));
        crates = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "crates.yml"));
    }

    private void saveResourceIfMissing(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
    }
}
