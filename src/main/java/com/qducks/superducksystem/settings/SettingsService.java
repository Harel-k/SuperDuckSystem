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
    private final Map<UUID, Map<PlayerSetting, Boolean>> cache = new ConcurrentHashMap<>();

    public SettingsService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Void> warm(UUID uuid) {
        return plugin.database().submit(connection -> {
            Map<PlayerSetting, Boolean> settings = defaults();
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
        Map<PlayerSetting, Boolean> settings = cache.get(uuid);
        if (settings == null) {
            return defaultValue(setting);
        }
        return settings.getOrDefault(setting, defaultValue(setting));
    }

    public CompletableFuture<Boolean> set(UUID uuid, PlayerSetting setting, boolean value) {
        Map<PlayerSetting, Boolean> settings = cache.computeIfAbsent(uuid, ignored -> defaults());
        boolean previous = settings.getOrDefault(setting, defaultValue(setting));
        settings.put(setting, value);

        CompletableFuture<Boolean> future = plugin.database().submit(connection -> {
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
        future.whenComplete((ignored, error) -> {
            if (error != null) {
                settings.put(setting, previous);
            }
        });
        return future;
    }

    public CompletableFuture<Boolean> toggle(UUID uuid, PlayerSetting setting) {
        return set(uuid, setting, !get(uuid, setting));
    }

    public CompletableFuture<Void> setAll(UUID uuid, Map<PlayerSetting, Boolean> requested) {
        Map<PlayerSetting, Boolean> settings = cache.computeIfAbsent(uuid, ignored -> defaults());
        EnumMap<PlayerSetting, Boolean> previous = new EnumMap<>(PlayerSetting.class);
        for (Map.Entry<PlayerSetting, Boolean> entry : requested.entrySet()) {
            previous.put(entry.getKey(), settings.getOrDefault(entry.getKey(), defaultValue(entry.getKey())));
            settings.put(entry.getKey(), entry.getValue());
        }

        CompletableFuture<Void> future = plugin.database().submit(connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO player_settings(uuid, setting, value) VALUES(?, ?, ?) " +
                            "ON CONFLICT(uuid, setting) DO UPDATE SET value=excluded.value")) {
                for (Map.Entry<PlayerSetting, Boolean> entry : requested.entrySet()) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, entry.getKey().name());
                    statement.setInt(3, entry.getValue() ? 1 : 0);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
                return null;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
        future.whenComplete((ignored, error) -> {
            if (error != null) {
                settings.putAll(previous);
            }
        });
        return future;
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    public boolean defaultValue(PlayerSetting setting) {
        return plugin.configs().settings().getBoolean("defaults." + setting.configKey(), setting.fallback());
    }

    private Map<PlayerSetting, Boolean> defaults() {
        Map<PlayerSetting, Boolean> values = new ConcurrentHashMap<>();
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
