package com.qducks.clientguard.policy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class StrikeStore {
    private final Plugin plugin;
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();

    public StrikeStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "strikes.yml");
        load();
    }

    public int getFreecamStrikes(UUID uuid) {
        return Math.max(0, data.getInt("players." + uuid + ".freecam", 0));
    }

    public int incrementFreecam(UUID uuid) {
        int next = getFreecamStrikes(uuid) + 1;
        data.set("players." + uuid + ".freecam", next);
        saveAtomic();
        return next;
    }

    public void clear(UUID uuid) {
        data.set("players." + uuid, null);
        saveAtomic();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            data.load(file);
        } catch (Exception exception) {
            plugin.getLogger().severe("Could not load ClientGuard strikes.yml: " + exception.getMessage());
        }
    }

    private void saveAtomic() {
        try {
            Files.createDirectories(file.toPath().getParent());
            Path temp = Files.createTempFile(file.toPath().getParent(), "strikes-", ".tmp");
            Files.writeString(temp, data.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not persist ClientGuard strikes.yml: " + exception.getMessage());
        }
    }
}
