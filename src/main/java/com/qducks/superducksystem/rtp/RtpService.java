package com.qducks.superducksystem.rtp;

import com.qducks.superducksystem.SuperDuckSystem;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class RtpService {
    private final SuperDuckSystem plugin;
    private final File configFile;
    private final NamespacedKey cooldownKey;
    private final Map<UUID, PendingRtp> pending = new java.util.HashMap<>();
    private final Set<Material> unsafeMaterials = new HashSet<>();
    private FileConfiguration config;

    public RtpService(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "rtp.yml");
        this.cooldownKey = new NamespacedKey(plugin, "rtp_last_success");
    }

    public void start() {
        if (!configFile.exists()) plugin.saveResource("rtp.yml", false);
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(configFile);
        unsafeMaterials.clear();
        for (String raw : config.getStringList("safety.unsafe-blocks")) {
            Material material = Material.matchMaterial(raw);
            if (material != null) unsafeMaterials.add(material);
        }
    }

    public void shutdown() {
        for (PendingRtp request : List.copyOf(pending.values())) {
            if (request.task != null) request.task.cancel();
        }
        pending.clear();
    }

    public boolean isPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void begin(Player player) {
        if (isPending(player)) {
            sendError(player, "An RTP is already in progress.");
            return;
        }

        World targetWorld = resolveTargetWorld(player);
        if (targetWorld == null) return;

        long cooldown = cooldownRemainingSeconds(player);
        if (cooldown > 0 && !player.hasPermission("superduck.rtp.bypass.cooldown")) {
            sendError(player, "You can RTP again in " + formatDuration(cooldown) + ".");
            return;
        }

        int warmup = Math.max(0, config.getInt("warmup.seconds", 3));
        PendingRtp request = new PendingRtp(player.getLocation().clone(), targetWorld);
        pending.put(player.getUniqueId(), request);

        if (warmup <= 0) {
            beginSearch(player, request);
            return;
        }

        BukkitRunnable runnable = new BukkitRunnable() {
            private int remaining = warmup;

            @Override
            public void run() {
                if (!player.isOnline() || pending.get(player.getUniqueId()) != request) {
                    cancel();
                    return;
                }

                if (remaining <= 0) {
                    request.task = null;
                    cancel();
                    beginSearch(player, request);
                    return;
                }

                sendAction(player, "Teleporting in " + remaining + "s... don't move");
                remaining--;
            }
        };
        request.task = runnable.runTaskTimer(plugin, 0L, 20L);
    }

    public void handleMove(Player player, Location to) {
        PendingRtp request = pending.get(player.getUniqueId());
        if (request == null || request.phase != Phase.WARMUP || to == null) return;
        if (!config.getBoolean("warmup.cancel-on-move", true)) return;

        Location start = request.start;
        if (start.getWorld() != to.getWorld()
                || distanceSquared(start, to) > config.getDouble("warmup.move-tolerance-squared", 0.01D)) {
            cancel(player, false);
        }
    }

    public void cancel(Player player, boolean silent) {
        PendingRtp request = pending.remove(player.getUniqueId());
        if (request == null) return;
        if (request.task != null) request.task.cancel();
        if (!silent) sendError(player, "RTP cancelled because you moved.");
    }

    private void beginSearch(Player player, PendingRtp request) {
        if (!player.isOnline() || pending.get(player.getUniqueId()) != request) return;
        request.phase = Phase.SEARCHING;
        sendAction(player, "Finding a random location...");
        searchAttempt(player, request, 1);
    }

    private void searchAttempt(Player player, PendingRtp request, int attempt) {
        if (!player.isOnline() || pending.get(player.getUniqueId()) != request) return;

        int maxAttempts = Math.max(1, config.getInt("safety.max-attempts", 32));
        if (attempt > maxAttempts) {
            pending.remove(player.getUniqueId());
            sendError(player, "Couldn't find a safe location. Try again.");
            return;
        }

        int[] point = randomPoint();
        int x = point[0];
        int z = point[1];
        World world = request.targetWorld;

        world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || pending.get(player.getUniqueId()) != request) return;
                    if (error != null || chunk == null) {
                        searchAttempt(player, request, attempt + 1);
                        return;
                    }

                    Location destination = safeDestination(player, world, x, z);
                    if (destination == null) {
                        searchAttempt(player, request, attempt + 1);
                        return;
                    }

                    preloadAndTeleport(player, request, destination);
                })
        );
    }

    private Location safeDestination(Player player, World world, int x, int z) {
        int minY = config.getInt("world.min-y", -64);
        int maxY = config.getInt("world.max-y", 320);
        int groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (groundY < minY || groundY + 2 > maxY) return null;

        Block ground = world.getBlockAt(x, groundY, z);
        Block feet = world.getBlockAt(x, groundY + 1, z);
        Block head = world.getBlockAt(x, groundY + 2, z);

        if (!ground.getType().isSolid()) return null;
        if (isUnsafe(ground.getType()) || isUnsafe(feet.getType()) || isUnsafe(head.getType())) return null;
        if (!feet.isPassable() || !head.isPassable()) return null;
        if (feet.isLiquid() || head.isLiquid()) return null;

        Location destination = new Location(world, x + 0.5D, groundY + 1.0D, z + 0.5D,
                player.getLocation().getYaw(), player.getLocation().getPitch());

        if (config.getBoolean("safety.respect-worldguard", true)) {
            if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
                plugin.getLogger().warning("RTP requires WorldGuard protection checks, but WorldGuard is not enabled.");
                return null;
            }
            try {
                ApplicableRegionSet regions = WorldGuard.getInstance().getPlatform()
                        .getRegionContainer().createQuery().getApplicableRegions(BukkitAdapter.adapt(destination));
                boolean insideNamedRegion = regions.getRegions().stream()
                        .anyMatch(region -> !region.getId().equalsIgnoreCase("__global__"));
                if (insideNamedRegion) return null;
            } catch (Throwable throwable) {
                plugin.getLogger().warning("RTP WorldGuard safety check failed: " + throwable.getMessage());
                return null;
            }
        }

        return destination;
    }

    private void preloadAndTeleport(Player player, PendingRtp request, Location destination) {
        request.phase = Phase.TELEPORTING;
        int radius = Math.max(0, Math.min(3, config.getInt("performance.preload-radius", 2)));
        int centerX = destination.getBlockX() >> 4;
        int centerZ = destination.getBlockZ() >> 4;
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                futures.add(destination.getWorld().getChunkAtAsync(centerX + dx, centerZ + dz, true));
            }
        }

        CompletableFuture<?>[] wrapped = futures.stream()
                .map(future -> future.handle((chunk, error) -> null))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(wrapped).whenComplete((ignored, preloadError) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || pending.get(player.getUniqueId()) != request) return;
                    player.teleportAsync(destination).whenComplete((success, teleportError) ->
                            Bukkit.getScheduler().runTask(plugin, () -> finishTeleport(player, request, success, teleportError))
                    );
                })
        );
    }

    private void finishTeleport(Player player, PendingRtp request, Boolean success, Throwable error) {
        if (pending.get(player.getUniqueId()) != request) return;
        pending.remove(player.getUniqueId());

        if (error != null || !Boolean.TRUE.equals(success)) {
            sendError(player, "Teleport failed. Try again.");
            return;
        }

        player.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, System.currentTimeMillis());
        player.setFallDistance(0.0F);
        int invulnerabilitySeconds = Math.max(0, config.getInt("safety.post-teleport-invulnerability-seconds", 5));
        if (invulnerabilitySeconds > 0) player.setNoDamageTicks(invulnerabilitySeconds * 20);
        sendAction(player, "Teleported!");
    }

    private World resolveTargetWorld(Player player) {
        String source = player.getWorld().getName();
        List<String> allowed = config.getStringList("world.allowed-source-worlds");
        boolean allowedSource = allowed.stream().anyMatch(name -> name.equalsIgnoreCase(source));
        if (!allowedSource) {
            sendError(player, "RTP is only available from Spawn or the Overworld.");
            return null;
        }

        String targetName = config.getString("world.target", "world");
        World target = Bukkit.getWorld(targetName);
        if (target == null) {
            sendError(player, "The RTP world is unavailable right now.");
            plugin.getLogger().severe("RTP target world does not exist: " + targetName);
            return null;
        }
        return target;
    }

    private int[] randomPoint() {
        double minRadius = Math.max(0, config.getDouble("world.min-radius", 1000.0D));
        double maxRadius = Math.max(minRadius + 1.0D, config.getDouble("world.max-radius", 15000.0D));
        double centerX = config.getDouble("world.center-x", 0.0D);
        double centerZ = config.getDouble("world.center-z", 0.0D);

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0.0D, Math.PI * 2.0D);
        double radiusSquared = random.nextDouble(minRadius * minRadius, maxRadius * maxRadius);
        double radius = Math.sqrt(radiusSquared);
        int x = (int) Math.round(centerX + Math.cos(angle) * radius);
        int z = (int) Math.round(centerZ + Math.sin(angle) * radius);
        return new int[]{x, z};
    }

    private long cooldownRemainingSeconds(Player player) {
        long cooldownSeconds = Math.max(0, config.getLong("cooldown.seconds", 300L));
        if (cooldownSeconds <= 0) return 0;
        Long last = player.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        if (last == null) return 0;
        long elapsed = Math.max(0L, System.currentTimeMillis() - last);
        long remainingMillis = cooldownSeconds * 1000L - elapsed;
        return remainingMillis <= 0 ? 0 : (remainingMillis + 999L) / 1000L;
    }

    private boolean isUnsafe(Material material) {
        return material == null || unsafeMaterials.contains(material);
    }

    private static double distanceSquared(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static String formatDuration(long seconds) {
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        if (minutes <= 0) return remainder + "s";
        if (remainder == 0) return minutes + "m";
        return minutes + "m " + remainder + "s";
    }

    private static void sendAction(Player player, String text) {
        player.sendActionBar(Component.text("RTP ", NamedTextColor.GOLD)
                .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, NamedTextColor.GRAY)));
    }

    private static void sendError(Player player, String text) {
        player.sendMessage(Component.text("RTP ", NamedTextColor.GOLD)
                .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, NamedTextColor.RED)));
    }

    private enum Phase { WARMUP, SEARCHING, TELEPORTING }

    private static final class PendingRtp {
        private final Location start;
        private final World targetWorld;
        private Phase phase = Phase.WARMUP;
        private BukkitTask task;

        private PendingRtp(Location start, World targetWorld) {
            this.start = start;
            this.targetWorld = targetWorld;
        }
    }
}
