package com.qducks.superduckclientguard;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

final class StrikeStore {
    private final SuperDuckClientGuard plugin;
    private final File file;
    private YamlConfiguration data;

    StrikeStore(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "strikes.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    synchronized int freecamStrikes(UUID playerId) {
        return data.getInt("freecam." + playerId + ".strikes", 0);
    }

    synchronized int incrementFreecam(UUID playerId, String playerName) {
        String root = "freecam." + playerId;
        int next = data.getInt(root + ".strikes", 0) + 1;
        data.set(root + ".strikes", next);
        data.set(root + ".last-name", playerName);
        data.set(root + ".last-detected-at", System.currentTimeMillis());
        saveAtomic();
        return next;
    }

    synchronized void resetFreecam(UUID playerId) {
        data.set("freecam." + playerId, null);
        saveAtomic();
    }

    private void saveAtomic() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create ClientGuard data folder");
        }

        File temp = new File(plugin.getDataFolder(), "strikes.yml.tmp");
        try {
            data.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to persist ClientGuard strike data: " + exception.getMessage());
            throw new IllegalStateException("Could not persist ClientGuard strike data", exception);
        } finally {
            if (temp.exists()) {
                try {
                    Files.deleteIfExists(temp.toPath());
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }
}
