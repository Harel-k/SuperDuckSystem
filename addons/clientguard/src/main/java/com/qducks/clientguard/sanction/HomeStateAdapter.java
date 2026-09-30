package com.qducks.clientguard.sanction;

import com.qducks.clientguard.SuperDuckClientGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HomeStateAdapter {
    private final SuperDuckClientGuard plugin;

    public HomeStateAdapter(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
    }

    public boolean ready() {
        if (!plugin.getConfig().getBoolean("external.homes.required", true)) return true;

        Plugin essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (essentials != null && essentials.isEnabled()) {
            return supportsEssentials(essentials);
        }

        Plugin huskHomes = plugin.getServer().getPluginManager().getPlugin("HuskHomes");
        if (huskHomes != null && huskHomes.isEnabled()) {
            // HuskHomes is deliberately blocked until the async snapshot/delete
            // adapter is implemented and verified against the live server version.
            return false;
        }

        // If no plugin even owns the normal home commands, there is no external
        // home state to wipe.
        return Bukkit.getPluginCommand("home") == null
                && Bukkit.getPluginCommand("sethome") == null
                && Bukkit.getPluginCommand("delhome") == null;
    }

    public HomeSnapshot capture(Player player) {
        if (!plugin.getConfig().getBoolean("external.homes.required", true)) {
            return new HomeSnapshot("disabled", List.of(), "");
        }

        Plugin essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (essentials != null && essentials.isEnabled() && supportsEssentials(essentials)) {
            return captureEssentials(essentials, player);
        }

        if (Bukkit.getPluginCommand("home") == null
                && Bukkit.getPluginCommand("sethome") == null
                && Bukkit.getPluginCommand("delhome") == null) {
            return new HomeSnapshot("none", List.of(), "");
        }

        throw new IllegalStateException(blocker());
    }

    public boolean wipe(Player player, HomeSnapshot snapshot) {
        if (snapshot == null) return false;
        if (snapshot.provider().equals("disabled") || snapshot.provider().equals("none")) return true;

        if (snapshot.provider().equals("EssentialsX")) {
            Plugin essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
            if (essentials == null || !essentials.isEnabled() || !supportsEssentials(essentials)) return false;
            return wipeEssentials(essentials, player.getUniqueId(), snapshot.homeNames());
        }

        return false;
    }

    public String blocker() {
        if (ready()) return "";

        Plugin huskHomes = plugin.getServer().getPluginManager().getPlugin("HuskHomes");
        if (huskHomes != null && huskHomes.isEnabled()) {
            return "HuskHomes is installed; its async snapshot+wipe adapter is not verified yet";
        }

        Plugin essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (essentials != null && essentials.isEnabled()) {
            return "EssentialsX is installed but its required homes API methods are unavailable";
        }

        return "A homes command is registered by an unsupported provider; refusing destructive sanctions";
    }

    private boolean supportsEssentials(Plugin essentials) {
        try {
            Method getUser = essentials.getClass().getMethod("getUser", UUID.class);
            Object probe = getUser.invoke(essentials, UUID.randomUUID());
            if (probe == null) {
                // Method existence is sufficient here; a random UUID is expected to be absent.
                return true;
            }
            probe.getClass().getMethod("getHomes");
            probe.getClass().getMethod("getHome", String.class);
            probe.getClass().getMethod("delHome", String.class);
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        } catch (ReflectiveOperationException exception) {
            // The API method exists; runtime lookup of a random user failing does
            // not make the integration structurally unsupported.
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private HomeSnapshot captureEssentials(Plugin essentials, Player player) {
        try {
            Object user = essentials.getClass().getMethod("getUser", UUID.class)
                    .invoke(essentials, player.getUniqueId());
            if (user == null) return new HomeSnapshot("EssentialsX", List.of(), "");

            Method getHomes = user.getClass().getMethod("getHomes");
            Method getHome = user.getClass().getMethod("getHome", String.class);
            List<String> names = new ArrayList<>((List<String>) getHomes.invoke(user));

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("provider", "EssentialsX");
            yaml.set("player-uuid", player.getUniqueId().toString());
            yaml.set("player-name", player.getName());

            for (String name : names) {
                Object value = getHome.invoke(user, name);
                if (!(value instanceof Location location)) continue;
                String root = "homes." + safeKey(name);
                yaml.set(root + ".name", name);
                yaml.set(root + ".world", location.getWorld() == null ? "" : location.getWorld().getName());
                yaml.set(root + ".x", location.getX());
                yaml.set(root + ".y", location.getY());
                yaml.set(root + ".z", location.getZ());
                yaml.set(root + ".yaw", location.getYaw());
                yaml.set(root + ".pitch", location.getPitch());
            }

            return new HomeSnapshot("EssentialsX", List.copyOf(names), yaml.saveToString());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not snapshot EssentialsX homes", exception);
        }
    }

    private boolean wipeEssentials(Plugin essentials, UUID uuid, List<String> homes) {
        try {
            Object user = essentials.getClass().getMethod("getUser", UUID.class).invoke(essentials, uuid);
            if (user == null) return homes.isEmpty();

            Method delete = user.getClass().getMethod("delHome", String.class);
            Method getHomes = user.getClass().getMethod("getHomes");
            for (String home : homes) {
                delete.invoke(user, home);
            }

            @SuppressWarnings("unchecked")
            List<String> remaining = (List<String>) getHomes.invoke(user);
            return remaining.isEmpty();
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().severe("Could not wipe EssentialsX homes for " + uuid + ": "
                    + exception.getMessage());
            return false;
        }
    }

    private String safeKey(String name) {
        return name.replace(".", "_").replace("[", "_").replace("]", "_");
    }

    public record HomeSnapshot(String provider, List<String> homeNames, String yaml) {
        public HomeSnapshot {
            homeNames = List.copyOf(homeNames);
            yaml = yaml == null ? "" : yaml;
        }
    }
}
