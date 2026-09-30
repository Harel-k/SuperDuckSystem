package com.qducks.clientguard.sanction;

import com.qducks.clientguard.SuperDuckClientGuard;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

public final class DuckyPvpAdapter {
    private final SuperDuckClientGuard plugin;

    public DuckyPvpAdapter(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
    }

    public boolean ready() {
        Plugin ducky = plugin.getServer().getPluginManager().getPlugin("DuckyPVP");
        if (ducky == null || !ducky.isEnabled()) return true;
        try {
            ducky.getClass().getMethod("exportPlayerBackup", UUID.class);
            ducky.getClass().getMethod("discardPlayerBackup", UUID.class);
            ducky.getClass().getMethod("hasPlayerBackup", UUID.class);
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    public String exportBackup(UUID uuid) {
        Plugin ducky = plugin.getServer().getPluginManager().getPlugin("DuckyPVP");
        if (ducky == null || !ducky.isEnabled()) return "";
        try {
            Method export = ducky.getClass().getMethod("exportPlayerBackup", UUID.class);
            Object value = export.invoke(ducky, uuid);
            return value instanceof String text ? text : "";
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not export DuckyPVP backup for " + uuid, exception);
        }
    }

    public boolean discardBackup(UUID uuid) {
        Plugin ducky = plugin.getServer().getPluginManager().getPlugin("DuckyPVP");
        if (ducky == null || !ducky.isEnabled()) return true;
        try {
            Method has = ducky.getClass().getMethod("hasPlayerBackup", UUID.class);
            boolean present = (Boolean) has.invoke(ducky, uuid);
            if (!present) return true;

            Method discard = ducky.getClass().getMethod("discardPlayerBackup", UUID.class);
            return (Boolean) discard.invoke(ducky, uuid);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().severe("Could not discard DuckyPVP backup for " + uuid + ": "
                    + exception.getMessage());
            return false;
        }
    }

    public String blocker() {
        return ready() ? "" : "DuckyPVP is installed but does not expose the ClientGuard-safe backup API";
    }
}
