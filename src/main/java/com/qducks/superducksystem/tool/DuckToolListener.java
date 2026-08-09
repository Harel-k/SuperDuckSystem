package com.qducks.superducksystem.tool;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class DuckToolListener implements Listener {
    private final SuperDuckSystem plugin;
    private final Set<UUID> recursiveBreakGuard = new HashSet<>();

    public DuckToolListener(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (recursiveBreakGuard.contains(player.getUniqueId()) || plugin.state().maintenance("custom-tools")) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        String itemId = plugin.customItems().getId(tool);
        if (itemId == null) return;

        switch (itemId.toLowerCase(Locale.ROOT)) {
            case "duck_pickaxe" -> breakArea(player, event.getBlock(), "duck_pickaxe");
            case "duck_shovel" -> breakArea(player, event.getBlock(), "duck_shovel");
            case "duck_axe" -> breakTree(player, event.getBlock());
            default -> { }
        }
    }

    private void breakArea(Player player, Block origin, String toolId) {
        FileConfiguration config = plugin.configs().customItems();
        String base = "tools." + toolId;
        if (!config.getBoolean(base + ".enabled", true) || !isAllowed(origin.getType(), toolId, config, base)) return;

        int width = oddClamp(config.getInt(base + ".width", 3), 1, 9);
        int height = oddClamp(config.getInt(base + ".height", 3), 1, 9);
        int depth = Math.max(1, Math.min(3, config.getInt(base + ".depth", 1)));
        int maxExtra = Math.max(0, Math.min(80, config.getInt(base + ".max-extra-blocks", width * height * depth - 1)));
        if (maxExtra == 0) return;

        List<Block> targets = areaTargets(player, origin, width, height, depth);
        int broken = 0;
        recursiveBreakGuard.add(player.getUniqueId());
        try {
            for (Block target : targets) {
                if (broken >= maxExtra || !player.isOnline()) break;
                if (sameBlock(origin, target) || target.getType().isAir()) continue;
                if (!isAllowed(target.getType(), toolId, config, base)) continue;
                if (player.breakBlock(target)) broken++;
            }
        } finally {
            recursiveBreakGuard.remove(player.getUniqueId());
        }
    }

    private List<Block> areaTargets(Player player, Block origin, int width, int height, int depth) {
        Vector direction = player.getEyeLocation().getDirection();
        double ax = Math.abs(direction.getX());
        double ay = Math.abs(direction.getY());
        double az = Math.abs(direction.getZ());
        int halfW = width / 2;
        int halfH = height / 2;
        List<Block> result = new ArrayList<>(width * height * depth);

        for (int d = 0; d < depth; d++) {
            int depthSign;
            if (ay >= ax && ay >= az) {
                depthSign = direction.getY() >= 0 ? 1 : -1;
                for (int a = -halfW; a <= halfW; a++) for (int b = -halfH; b <= halfH; b++) result.add(origin.getRelative(a, d * depthSign, b));
            } else if (ax >= az) {
                depthSign = direction.getX() >= 0 ? 1 : -1;
                for (int a = -halfW; a <= halfW; a++) for (int b = -halfH; b <= halfH; b++) result.add(origin.getRelative(d * depthSign, b, a));
            } else {
                depthSign = direction.getZ() >= 0 ? 1 : -1;
                for (int a = -halfW; a <= halfW; a++) for (int b = -halfH; b <= halfH; b++) result.add(origin.getRelative(a, b, d * depthSign));
            }
        }
        return result;
    }

    private void breakTree(Player player, Block origin) {
        FileConfiguration config = plugin.configs().customItems();
        String base = "tools.duck_axe";
        if (!config.getBoolean(base + ".enabled", true)
                || !config.getBoolean(base + ".tree-vein-enabled", true)
                || !isTreeBlock(origin.getType(), config, base)) return;

        int maxBlocks = Math.max(1, Math.min(1024, config.getInt(base + ".max-blocks", 256)));
        int maxRadius = Math.max(1, Math.min(64, config.getInt(base + ".max-radius", 16)));
        Set<BlockKey> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        List<Block> connected = new ArrayList<>();
        queue.add(origin);

        while (!queue.isEmpty() && connected.size() < maxBlocks) {
            Block current = queue.removeFirst();
            BlockKey key = BlockKey.of(current);
            if (!visited.add(key)) continue;
            if (Math.abs(current.getX() - origin.getX()) > maxRadius
                    || Math.abs(current.getY() - origin.getY()) > maxRadius
                    || Math.abs(current.getZ() - origin.getZ()) > maxRadius) continue;
            if (!isTreeBlock(current.getType(), config, base)) continue;
            connected.add(current);
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        queue.addLast(current.getRelative(x, y, z));
                    }
                }
            }
        }

        recursiveBreakGuard.add(player.getUniqueId());
        try {
            for (Block target : connected) {
                if (!player.isOnline()) break;
                if (sameBlock(origin, target) || target.getType().isAir()) continue;
                if (!isTreeBlock(target.getType(), config, base)) continue;
                player.breakBlock(target);
            }
        } finally {
            recursiveBreakGuard.remove(player.getUniqueId());
        }
    }

    private boolean isAllowed(Material material, String toolId, FileConfiguration config, String base) {
        if (matchesConfigured(material, config.getStringList(base + ".blocked-blocks"))) return false;
        List<String> allowed = config.getStringList(base + ".allowed-blocks");
        if (!allowed.isEmpty()) return matchesConfigured(material, allowed);
        String name = material.name();
        if (toolId.equals("duck_shovel")) {
            return name.contains("DIRT") || name.contains("SAND") || name.contains("GRAVEL")
                    || name.contains("CLAY") || name.contains("SNOW") || name.equals("GRASS_BLOCK")
                    || name.equals("PODZOL") || name.equals("MYCELIUM") || name.equals("SOUL_SAND")
                    || name.equals("SOUL_SOIL") || name.equals("MUD") || name.equals("MUDDY_MANGROVE_ROOTS");
        }
        return material.isBlock() && !material.isAir()
                && !name.contains("CHEST") && !name.contains("SHULKER")
                && !name.contains("FURNACE") && !name.contains("SPAWNER")
                && !name.endsWith("_LOG") && !name.endsWith("_WOOD")
                && !name.endsWith("_PLANKS") && !name.contains("LEAVES")
                && material != Material.WATER && material != Material.LAVA;
    }

    private boolean isTreeBlock(Material material, FileConfiguration config, String base) {
        List<String> allowed = config.getStringList(base + ".allowed-blocks");
        if (!allowed.isEmpty()) return matchesConfigured(material, allowed);
        String name = material.name();
        if (name.endsWith("_LOG")) return true;
        if (config.getBoolean(base + ".include-wood-blocks", true) && name.endsWith("_WOOD")) return true;
        return config.getBoolean(base + ".include-stems", true) && (name.endsWith("_STEM") || name.endsWith("_HYPHAE"));
    }

    private boolean matchesConfigured(Material material, List<String> configured) {
        for (String raw : configured) {
            Material found = Material.matchMaterial(raw);
            if (found == material) return true;
        }
        return false;
    }

    private int oddClamp(int value, int min, int max) {
        int clamped = Math.max(min, Math.min(max, value));
        return clamped % 2 == 0 ? Math.max(min, clamped - 1) : clamped;
    }

    private boolean sameBlock(Block a, Block b) {
        return a.getWorld().equals(b.getWorld()) && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
