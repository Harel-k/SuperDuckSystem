package com.qducks.clientguard.sanction;

import com.qducks.clientguard.SuperDuckClientGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HomeStateAdapter {
    private final SuperDuckClientGuard plugin;

    public HomeStateAdapter(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
    }

    public boolean ready() {
        if (!plugin.getConfig().getBoolean("external.homes.required", true)) return true;
        return switch (selectedProvider()) {
            case ESSENTIALS -> supportsEssentials();
            case HUSKHOMES -> supportsHuskHomes();
            case NONE -> true;
            case UNSUPPORTED, AMBIGUOUS -> false;
        };
    }

    public CompletableFuture<HomeSnapshot> capture(Player player) {
        return switch (selectedProvider()) {
            case ESSENTIALS -> completedCapture(() -> captureEssentials(player));
            case HUSKHOMES -> captureHuskHomes(player);
            case NONE -> CompletableFuture.completedFuture(new HomeSnapshot("none", List.of(), ""));
            case UNSUPPORTED, AMBIGUOUS -> CompletableFuture.failedFuture(new IllegalStateException(blocker()));
        };
    }

    public CompletableFuture<Boolean> wipe(Player player, HomeSnapshot snapshot) {
        if (snapshot == null) return CompletableFuture.completedFuture(false);
        return switch (snapshot.provider()) {
            case "none", "disabled" -> CompletableFuture.completedFuture(true);
            case "EssentialsX" -> CompletableFuture.completedFuture(wipeEssentials(player.getUniqueId(), snapshot.homeNames()));
            case "HuskHomes" -> wipeHuskHomes(player, snapshot.homeNames());
            default -> CompletableFuture.completedFuture(false);
        };
    }

    public String blocker() {
        if (ready()) return "";
        return switch (selectedProvider()) {
            case HUSKHOMES -> "HuskHomes is installed but its required API methods are unavailable";
            case ESSENTIALS -> "EssentialsX is installed but its required homes API methods are unavailable";
            case AMBIGUOUS -> "Multiple supported homes providers are enabled; set external.homes.provider explicitly";
            case UNSUPPORTED -> "A homes command is registered by an unsupported provider";
            case NONE -> "";
        };
    }

    private Provider selectedProvider() {
        if (!plugin.getConfig().getBoolean("external.homes.required", true)) return Provider.NONE;

        String requested = plugin.getConfig().getString("external.homes.provider", "auto").trim().toLowerCase();
        boolean essentials = enabled("Essentials");
        boolean husk = enabled("HuskHomes");

        if (requested.equals("essentials") || requested.equals("essentialsx")) {
            return essentials ? Provider.ESSENTIALS : Provider.UNSUPPORTED;
        }
        if (requested.equals("huskhomes") || requested.equals("husk")) {
            return husk ? Provider.HUSKHOMES : Provider.UNSUPPORTED;
        }
        if (!requested.equals("auto")) return Provider.UNSUPPORTED;

        if (essentials && husk) return Provider.AMBIGUOUS;
        if (essentials) return Provider.ESSENTIALS;
        if (husk) return Provider.HUSKHOMES;

        if (Bukkit.getPluginCommand("home") == null
                && Bukkit.getPluginCommand("sethome") == null
                && Bukkit.getPluginCommand("delhome") == null) {
            return Provider.NONE;
        }
        return Provider.UNSUPPORTED;
    }

    private boolean enabled(String name) {
        Plugin candidate = plugin.getServer().getPluginManager().getPlugin(name);
        return candidate != null && candidate.isEnabled();
    }

    private boolean supportsEssentials() {
        Plugin essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (essentials == null) return false;
        try {
            Method getUser = essentials.getClass().getMethod("getUser", UUID.class);
            Class<?> userType = getUser.getReturnType();
            userType.getMethod("getHomes");
            userType.getMethod("getHome", String.class);
            userType.getMethod("delHome", String.class);
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    private HomeSnapshot captureEssentials(Player player) throws Exception {
        Plugin essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (essentials == null || !essentials.isEnabled()) throw new IllegalStateException("EssentialsX is unavailable");

        Object user = essentials.getClass().getMethod("getUser", UUID.class)
                .invoke(essentials, player.getUniqueId());
        if (user == null) return new HomeSnapshot("EssentialsX", List.of(), "");

        @SuppressWarnings("unchecked")
        List<String> names = new ArrayList<>((List<String>) user.getClass().getMethod("getHomes").invoke(user));
        Method getHome = user.getClass().getMethod("getHome", String.class);

        YamlConfiguration yaml = baseYaml("EssentialsX", player);
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
    }

    private boolean wipeEssentials(UUID uuid, List<String> homes) {
        Plugin essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (essentials == null || !essentials.isEnabled()) return false;
        try {
            Object user = essentials.getClass().getMethod("getUser", UUID.class).invoke(essentials, uuid);
            if (user == null) return homes.isEmpty();

            Method delete = user.getClass().getMethod("delHome", String.class);
            Method getHomes = user.getClass().getMethod("getHomes");
            for (String home : homes) delete.invoke(user, home);

            @SuppressWarnings("unchecked")
            List<String> remaining = (List<String>) getHomes.invoke(user);
            return remaining.isEmpty();
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().severe("Could not wipe EssentialsX homes for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    private boolean supportsHuskHomes() {
        try {
            Class<?> apiType = Class.forName("net.william278.huskhomes.api.HuskHomesAPI");
            apiType.getMethod("getInstance");
            apiType.getMethod("adaptUser", Player.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    private CompletableFuture<HomeSnapshot> captureHuskHomes(Player player) {
        try {
            Object api = huskApi();
            Object user = api.getClass().getMethod("adaptUser", Player.class).invoke(api, player);
            Method getUserHomes = findCompatible(api.getClass(), "getUserHomes", user.getClass());
            Object rawFuture = getUserHomes.invoke(api, user);
            if (!(rawFuture instanceof CompletableFuture<?> future)) {
                return CompletableFuture.failedFuture(new IllegalStateException("HuskHomes getUserHomes did not return a future"));
            }

            return future.thenApply(raw -> {
                if (!(raw instanceof Collection<?> homes)) {
                    throw new IllegalStateException("HuskHomes returned an unexpected homes result");
                }
                return serializeHuskHomes(player, homes);
            });
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private HomeSnapshot serializeHuskHomes(Player player, Collection<?> homes) {
        try {
            YamlConfiguration yaml = baseYaml("HuskHomes", player);
            List<String> names = new ArrayList<>();

            for (Object home : homes) {
                String name = String.valueOf(home.getClass().getMethod("getName").invoke(home));
                names.add(name);
                String root = "homes." + safeKey(name);
                yaml.set(root + ".name", name);
                yaml.set(root + ".uuid", String.valueOf(home.getClass().getMethod("getUuid").invoke(home)));
                yaml.set(root + ".server", String.valueOf(home.getClass().getMethod("getServer").invoke(home)));
                yaml.set(root + ".x", asNumber(home.getClass().getMethod("getX").invoke(home)).doubleValue());
                yaml.set(root + ".y", asNumber(home.getClass().getMethod("getY").invoke(home)).doubleValue());
                yaml.set(root + ".z", asNumber(home.getClass().getMethod("getZ").invoke(home)).doubleValue());
                yaml.set(root + ".yaw", asNumber(home.getClass().getMethod("getYaw").invoke(home)).floatValue());
                yaml.set(root + ".pitch", asNumber(home.getClass().getMethod("getPitch").invoke(home)).floatValue());

                Object world = home.getClass().getMethod("getWorld").invoke(home);
                if (world != null) {
                    yaml.set(root + ".world", String.valueOf(world.getClass().getMethod("getName").invoke(world)));
                }

                Object meta = home.getClass().getMethod("getMeta").invoke(home);
                if (meta != null) {
                    yaml.set(root + ".description",
                            String.valueOf(meta.getClass().getMethod("getDescription").invoke(meta)));
                    Object tags = meta.getClass().getMethod("getTags").invoke(meta);
                    if (tags instanceof Map<?, ?> map) yaml.set(root + ".tags", map);
                    yaml.set(root + ".created",
                            String.valueOf(meta.getClass().getMethod("getCreationTimestamp").invoke(meta)));
                }
            }

            return new HomeSnapshot("HuskHomes", List.copyOf(names), yaml.saveToString());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not serialize HuskHomes snapshot", exception);
        }
    }

    private CompletableFuture<Boolean> wipeHuskHomes(Player player, List<String> homes) {
        try {
            Object api = huskApi();
            Object user = api.getClass().getMethod("adaptUser", Player.class).invoke(api, player);
            Method deleteHome = findCompatible(api.getClass(), "deleteHome", user.getClass(), String.class);
            for (String name : homes) deleteHome.invoke(api, user, name);

            CompletableFuture<Boolean> result = new CompletableFuture<>();
            verifyHuskDeleted(api, user, homes, result, 1);
            return result;
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private void verifyHuskDeleted(Object api, Object user, List<String> expected,
                                   CompletableFuture<Boolean> result, int attempt) {
        if (result.isDone()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                Method getUserHomes = findCompatible(api.getClass(), "getUserHomes", user.getClass());
                Object rawFuture = getUserHomes.invoke(api, user);
                if (!(rawFuture instanceof CompletableFuture<?> future)) {
                    result.complete(false);
                    return;
                }
                future.whenComplete((raw, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    boolean remaining = false;
                    if (raw instanceof Collection<?> homes) {
                        for (Object home : homes) {
                            try {
                                String name = String.valueOf(home.getClass().getMethod("getName").invoke(home));
                                if (expected.contains(name)) {
                                    remaining = true;
                                    break;
                                }
                            } catch (ReflectiveOperationException reflectionError) {
                                result.completeExceptionally(reflectionError);
                                return;
                            }
                        }
                    }
                    if (!remaining) {
                        result.complete(true);
                    } else if (attempt >= 5) {
                        result.complete(false);
                    } else {
                        verifyHuskDeleted(api, user, expected, result, attempt + 1);
                    }
                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }, 10L);
    }

    private Object huskApi() throws Exception {
        Class<?> apiType = Class.forName("net.william278.huskhomes.api.HuskHomesAPI");
        return apiType.getMethod("getInstance").invoke(null);
    }

    private Method findCompatible(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < params.length; i++) {
                if (!params[i].isAssignableFrom(args[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return method;
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private Number asNumber(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalStateException("Expected numeric HuskHomes position value");
    }

    private <T> CompletableFuture<T> completedCapture(ThrowingSupplier<T> supplier) {
        try {
            return CompletableFuture.completedFuture(supplier.get());
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private YamlConfiguration baseYaml(String provider, Player player) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("provider", provider);
        yaml.set("player-uuid", player.getUniqueId().toString());
        yaml.set("player-name", player.getName());
        return yaml;
    }

    private String safeKey(String name) {
        return name.replace(".", "_").replace("[", "_").replace("]", "_");
    }

    private enum Provider {
        ESSENTIALS,
        HUSKHOMES,
        NONE,
        UNSUPPORTED,
        AMBIGUOUS
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public record HomeSnapshot(String provider, List<String> homeNames, String yaml) {
        public HomeSnapshot {
            homeNames = List.copyOf(homeNames);
            yaml = yaml == null ? "" : yaml;
        }
    }
}
