package com.qducks.clientguard.detect;

import com.qducks.clientguard.SuperDuckClientGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the original block data of every temporary scan block (sign + barrier) before it is
 * placed, so a crash mid-scan can't leave them in the world: leftovers are restored on startup.
 */
final class ScanBlockJournal {
    private final SuperDuckClientGuard plugin;
    private final File file;
    private final Map<String, String> pending = new LinkedHashMap<>();

    ScanBlockJournal(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending-scan-blocks.yml");
    }

    void record(Location location, BlockData original) {
        pending.put(key(location), original.getAsString());
        save();
    }

    void clear(Location location) {
        if (pending.remove(key(location)) != null) save();
    }

    /** Restores blocks left behind by a previous run. Entries for worlds that aren't loaded are kept. */
    void restoreLeftovers() {
        if (!file.isFile()) return;
        YamlConfiguration stored = new YamlConfiguration();
        stored.options().pathSeparator('|');
        try {
            stored.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not read the scan block journal (" + exception.getMessage()
                    + "); leaving it in place for manual recovery.");
            return;
        }
        ConfigurationSection blocks = stored.getConfigurationSection("blocks");
        if (blocks == null) return;
        int restored = 0;
        for (String key : blocks.getKeys(false)) {
            String data = blocks.getString(key);
            Location location = parse(key);
            if (data == null || location == null) {
                if (data != null) pending.put(key, data);
                continue;
            }
            try {
                location.getBlock().setBlockData(Bukkit.createBlockData(data), false);
                restored++;
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid scan block journal entry " + key + ": " + exception.getMessage());
            }
        }
        save();
        if (restored > 0) {
            plugin.getLogger().warning("Restored " + restored + " temporary scan block(s) left by an interrupted scan.");
        }
    }

    private void save() {
        try {
            if (pending.isEmpty()) {
                Files.deleteIfExists(file.toPath());
                return;
            }
            YamlConfiguration data = new YamlConfiguration();
            data.options().pathSeparator('|');
            pending.forEach((key, value) -> data.set("blocks|" + key, value));
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "pending-scan-blocks-", ".tmp");
            try {
                Files.writeString(temp, data.saveToString(), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save the scan block journal: " + exception.getMessage());
        }
    }

    // world;x;y;z (the YAML path separator is '|', so world names with dots are safe)
    private static String key(Location location) {
        return location.getWorld().getName() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
    }

    private static Location parse(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) return null;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
