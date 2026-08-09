package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CrateLocationService implements Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SuperDuckSystem plugin;
    private final CrateService crates;
    private final KeyService keys;
    private final CrateMenu menu;
    private final NamespacedKey hologramKey;
    private final Map<BlockKey, CrateLocation> locations = new HashMap<>();

    public CrateLocationService(SuperDuckSystem plugin, CrateService crates, KeyService keys) {
        this.plugin = plugin;
        this.crates = crates;
        this.keys = keys;
        this.menu = new CrateMenu(plugin, keys, crates);
        this.hologramKey = new NamespacedKey(plugin, "crate_hologram");
    }

    public void start() {
        reload();
    }

    public void stop() {
        removeAllHolograms();
        locations.clear();
    }

    public void reload() {
        removeAllHolograms();
        locations.clear();

        FileConfiguration config = plugin.configs().crateLocations();
        ConfigurationSection section = config.getConfigurationSection("locations");
        if (section == null) {
            plugin.getLogger().info("No physical crate locations configured.");
            return;
        }

        for (String locationId : section.getKeys(false)) {
            String base = "locations." + locationId;
            if (!config.getBoolean(base + ".enabled", false)) {
                continue;
            }

            String crateId = normalize(config.getString(base + ".crate", locationId));
            if (!crateExists(crateId)) {
                plugin.getLogger().warning("Physical crate '" + locationId + "' references unknown crate '" + crateId + "'.");
                continue;
            }

            String worldName = config.getString(base + ".world", "spawn");
            if (worldName == null || worldName.isBlank()) {
                plugin.getLogger().warning("Physical crate '" + locationId + "' has no world configured.");
                continue;
            }

            int x = config.getInt(base + ".x");
            int y = config.getInt(base + ".y");
            int z = config.getInt(base + ".z");
            Material expected = material(config.getString(base + ".expected-block"));
            CrateLocation location = new CrateLocation(locationId, crateId, worldName, x, y, z, expected);
            BlockKey key = new BlockKey(worldName.toLowerCase(Locale.ROOT), x, y, z);
            CrateLocation previous = locations.put(key, location);
            if (previous != null) {
                plugin.getLogger().warning("Physical crates '" + previous.id() + "' and '" + locationId
                        + "' use the same block coordinate. The last one wins.");
            }
        }

        for (World world : Bukkit.getWorlds()) {
            spawnForWorld(world);
        }
        plugin.getLogger().info("Loaded " + locations.size() + " physical crate block(s).");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        var block = event.getClickedBlock();
        BlockKey key = new BlockKey(
                block.getWorld().getName().toLowerCase(Locale.ROOT),
                block.getX(),
                block.getY(),
                block.getZ()
        );
        CrateLocation crate = locations.get(key);
        if (crate == null) {
            return;
        }

        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);
        menu.openPhysicalCrate(event.getPlayer(), crate.crateId());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        spawnForWorld(event.getWorld());
    }

    private void spawnForWorld(World world) {
        for (CrateLocation crate : locations.values()) {
            if (!crate.worldName().equalsIgnoreCase(world.getName())) {
                continue;
            }
            validateBlock(world, crate);
            spawnHologram(world, crate);
        }
    }

    private void validateBlock(World world, CrateLocation crate) {
        if (crate.expectedBlock() == null) {
            return;
        }
        // There are only a handful of spawn crates, so loading their chunks here is intentional.
        world.getChunkAt(crate.x() >> 4, crate.z() >> 4);
        Material actual = world.getBlockAt(crate.x(), crate.y(), crate.z()).getType();
        if (actual != crate.expectedBlock()) {
            plugin.getLogger().warning("Physical crate '" + crate.id() + "' expected " + crate.expectedBlock()
                    + " at " + crate.worldName() + " " + crate.x() + " " + crate.y() + " " + crate.z()
                    + " but found " + actual + ". The click location still remains active.");
        }
    }

    private void spawnHologram(World world, CrateLocation crate) {
        FileConfiguration config = plugin.configs().crateLocations();
        String base = "locations." + crate.id() + ".hologram";
        boolean enabled = config.isSet(base + ".enabled")
                ? config.getBoolean(base + ".enabled")
                : config.getBoolean("hologram-defaults.enabled", true);
        if (!enabled) {
            return;
        }

        removeHologram(world, crate.id());

        double height = config.isSet(base + ".height")
                ? config.getDouble(base + ".height")
                : config.getDouble("hologram-defaults.height", 1.75);
        boolean shadowed = config.isSet(base + ".shadowed")
                ? config.getBoolean(base + ".shadowed")
                : config.getBoolean("hologram-defaults.shadowed", true);
        boolean seeThrough = config.isSet(base + ".see-through")
                ? config.getBoolean(base + ".see-through")
                : config.getBoolean("hologram-defaults.see-through", false);
        boolean defaultBackground = config.isSet(base + ".default-background")
                ? config.getBoolean(base + ".default-background")
                : config.getBoolean("hologram-defaults.default-background", false);
        int lineWidth = config.isSet(base + ".line-width")
                ? config.getInt(base + ".line-width")
                : config.getInt("hologram-defaults.line-width", 220);
        float viewRange = (float) (config.isSet(base + ".view-range")
                ? config.getDouble(base + ".view-range")
                : config.getDouble("hologram-defaults.view-range", 32.0));

        List<String> lines = config.getStringList(base + ".lines");
        if (lines.isEmpty()) {
            lines = config.getStringList("hologram-defaults.lines");
        }
        if (lines.isEmpty()) {
            lines = List.of(
                    "<yellow><bold>%crate%</bold></yellow>",
                    "<gray>Right-click to view rewards</gray>"
            );
        }

        Component text = hologramText(lines, crate);
        Location location = new Location(world, crate.x() + 0.5, crate.y() + height, crate.z() + 0.5);
        world.getChunkAt(crate.x() >> 4, crate.z() >> 4);
        world.spawn(location, TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(shadowed);
            display.setSeeThrough(seeThrough);
            display.setDefaultBackground(defaultBackground);
            display.setLineWidth(Math.max(20, lineWidth));
            display.setViewRange(Math.max(1.0f, viewRange));
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setPersistent(false);
            display.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, crate.id());
        });
    }

    private Component hologramText(List<String> lines, CrateLocation crate) {
        List<Component> components = new ArrayList<>();
        String crateName = escape(crates.displayName(crate.crateId()));
        String keyName = escape(keys.keyDisplayName(crates.keyId(crate.crateId())));
        for (String raw : lines) {
            String line = raw
                    .replace("%crate%", crateName)
                    .replace("%key%", keyName);
            components.add(MINI.deserialize(line));
        }

        Component out = Component.empty();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(components.get(i));
        }
        return out;
    }

    private void removeAllHolograms() {
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(hologramKey, PersistentDataType.STRING)) {
                    display.remove();
                }
            }
        }
    }

    private void removeHologram(World world, String locationId) {
        for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
            String tagged = display.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
            if (locationId.equals(tagged)) {
                display.remove();
            }
        }
    }

    private boolean crateExists(String crateId) {
        return crates.configuredCrates().stream().anyMatch(id -> id.equalsIgnoreCase(crateId));
    }

    private Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Material.matchMaterial(raw);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<");
    }

    private record BlockKey(String world, int x, int y, int z) {
    }

    private record CrateLocation(
            String id,
            String crateId,
            String worldName,
            int x,
            int y,
            int z,
            Material expectedBlock
    ) {
    }
}
