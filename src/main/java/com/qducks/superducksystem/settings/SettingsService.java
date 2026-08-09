package com.qducks.superducksystem.settings;

import com.qducks.superducksystem.SuperDuckSystem;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class SettingsService {
    private final SuperDuckSystem plugin;
    private final Map<UUID, EnumMap<PlayerSetting, Boolean>> cache = new ConcurrentHashMap<>();

    public SettingsService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Void> warm(UUID uuid) {
        return plugin.database().submit(connection -> {
            EnumMap<PlayerSetting, Boolean> settings = defaults();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT setting, value FROM player_settings WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        PlayerSetting setting = find(result.getString("setting"));
                        if (setting != null) {
                            settings.put(setting, result.getInt("value") != 0);
                        }
                    }
                }
            }
            cache.put(uuid, settings);
            return null;
        });
    }

    public boolean get(UUID uuid, PlayerSetting setting) {
        EnumMap<PlayerSetting, Boolean> settings = cache.get(uuid);
        if (settings == null) {
            return defaultValue(setting);
        }
        return settings.getOrDefault(setting, defaultValue(setting));
    }

    public CompletableFuture<Boolean> set(UUID uuid, PlayerSetting setting, boolean value) {
        cache.computeIfAbsent(uuid, ignored -> defaults()).put(setting, value);
        return plugin.database().submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO player_settings(uuid, setting, value) VALUES(?, ?, ?) " +
                            "ON CONFLICT(uuid, setting) DO UPDATE SET value=excluded.value")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, setting.name());
                statement.setInt(3, value ? 1 : 0);
                statement.executeUpdate();
            }
            return value;
        });
    }

    public CompletableFuture<Boolean> toggle(UUID uuid, PlayerSetting setting) {
        return set(uuid, setting, !get(uuid, setting));
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    public boolean defaultValue(PlayerSetting setting) {
        return plugin.configs().settings().getBoolean("defaults." + setting.configKey(), setting.fallback());
    }

    private EnumMap<PlayerSetting, Boolean> defaults() {
        EnumMap<PlayerSetting, Boolean> values = new EnumMap<>(PlayerSetting.class);
        for (PlayerSetting setting : PlayerSetting.values()) {
            values.put(setting, defaultValue(setting));
        }
        return values;
    }

    private PlayerSetting find(String raw) {
        try {
            return PlayerSetting.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
