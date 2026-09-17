package com.qducks.superducksystem.adminmode;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class PlayerStateCodec {
    private PlayerStateCodec() {}

    static void capture(Player player, ConfigurationSection section, BigDecimal money, BigDecimal ducks) {
        section.set("gamemode", player.getGameMode().name());
        section.set("level", player.getLevel());
        section.set("exp", player.getExp());
        section.set("total-experience", player.getTotalExperience());
        section.set("health", player.getHealth());
        section.set("absorption", player.getAbsorptionAmount());
        section.set("food", player.getFoodLevel());
        section.set("saturation", player.getSaturation());
        section.set("exhaustion", player.getExhaustion());
        section.set("fire-ticks", player.getFireTicks());
        section.set("remaining-air", player.getRemainingAir());
        section.set("freeze-ticks", player.getFreezeTicks());
        section.set("allow-flight", player.getAllowFlight());
        section.set("flying", player.isFlying());
        section.set("fly-speed", player.getFlySpeed());
        section.set("walk-speed", player.getWalkSpeed());
        Location loc = player.getLocation();
        section.set("location.world", loc.getWorld().getName());
        section.set("location.x", loc.getX());
        section.set("location.y", loc.getY());
        section.set("location.z", loc.getZ());
        section.set("location.yaw", loc.getYaw());
        section.set("location.pitch", loc.getPitch());
        section.set("economy.money", money.toPlainString());
        section.set("economy.ducks", ducks.toPlainString());
        saveItems(section, "inventory", player.getInventory().getStorageContents());
        saveItems(section, "armor", player.getInventory().getArmorContents());
        section.set("offhand", player.getInventory().getItemInOffHand());
        saveItems(section, "ender-chest", player.getEnderChest().getContents());
        section.set("effects", null);
        List<String> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(effect.getType().getKey() + ";" + effect.getDuration() + ";" + effect.getAmplifier() + ";" + effect.isAmbient() + ";" + effect.hasParticles() + ";" + effect.hasIcon());
        }
        section.set("effects", effects);
    }

    static void createFreshAdmin(Player player, ConfigurationSection section, BigDecimal money, BigDecimal ducks, GameMode gameMode) {
        section.set("gamemode", gameMode.name());
        section.set("level", 0);
        section.set("exp", 0.0);
        section.set("total-experience", 0);
        section.set("health", player.getMaxHealth());
        section.set("absorption", 0.0);
        section.set("food", 20);
        section.set("saturation", 5.0);
        section.set("exhaustion", 0.0);
        section.set("fire-ticks", 0);
        section.set("remaining-air", player.getMaximumAir());
        section.set("freeze-ticks", 0);
        section.set("allow-flight", gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR);
        section.set("flying", false);
        section.set("fly-speed", 0.1);
        section.set("walk-speed", 0.2);
        Location loc = player.getLocation();
        section.set("location.world", loc.getWorld().getName());
        section.set("location.x", loc.getX());
        section.set("location.y", loc.getY());
        section.set("location.z", loc.getZ());
        section.set("location.yaw", loc.getYaw());
        section.set("location.pitch", loc.getPitch());
        section.set("economy.money", money.toPlainString());
        section.set("economy.ducks", ducks.toPlainString());
        saveItems(section, "inventory", new ItemStack[36]);
        saveItems(section, "armor", new ItemStack[4]);
        section.set("offhand", null);
        saveItems(section, "ender-chest", new ItemStack[player.getEnderChest().getSize()]);
        section.set("effects", List.of());
    }

    static boolean apply(Player player, ConfigurationSection section) {
        if (section == null) return false;

        // Restore the profile itself first. The saved location is deliberately applied
        // last so another state change in this method cannot immediately interfere with
        // the profile teleport.
        player.getInventory().setStorageContents(loadItems(section, "inventory", 36));
        player.getInventory().setArmorContents(loadItems(section, "armor", 4));
        player.getInventory().setItemInOffHand(section.getItemStack("offhand"));
        ItemStack[] savedEnder = loadItems(section, "ender-chest", player.getEnderChest().getSize());
        ItemStack[] ender = new ItemStack[player.getEnderChest().getSize()];
        System.arraycopy(savedEnder, 0, ender, 0, Math.min(savedEnder.length, ender.length));
        player.getEnderChest().setContents(ender);

        GameMode gameMode;
        try { gameMode = GameMode.valueOf(section.getString("gamemode", "SURVIVAL")); }
        catch (IllegalArgumentException ex) { gameMode = GameMode.SURVIVAL; }
        player.setGameMode(gameMode);
        boolean allowFlight = section.getBoolean("allow-flight") || gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR;
        player.setAllowFlight(allowFlight);
        player.setFlySpeed(clamp((float) section.getDouble("fly-speed", 0.1), 0.1F));
        player.setWalkSpeed(clamp((float) section.getDouble("walk-speed", 0.2), 0.2F));
        player.setFlying(section.getBoolean("flying") && allowFlight);
        player.setLevel(Math.max(0, section.getInt("level")));
        player.setExp(Math.max(0F, Math.min(0.999999F, (float) section.getDouble("exp"))));
        player.setTotalExperience(Math.max(0, section.getInt("total-experience")));
        player.setHealth(Math.max(0.1, Math.min(section.getDouble("health", 20), player.getMaxHealth())));
        player.setAbsorptionAmount(Math.max(0, section.getDouble("absorption")));
        player.setFoodLevel(Math.max(0, Math.min(20, section.getInt("food", 20))));
        player.setSaturation(Math.max(0, (float) section.getDouble("saturation", 5)));
        player.setExhaustion(Math.max(0, (float) section.getDouble("exhaustion")));
        player.setFireTicks(Math.max(0, section.getInt("fire-ticks")));
        player.setRemainingAir(Math.max(0, Math.min(player.getMaximumAir(), section.getInt("remaining-air", player.getMaximumAir()))));
        player.setFreezeTicks(Math.max(0, section.getInt("freeze-ticks")));
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        for (String raw : section.getStringList("effects")) addEffect(player, raw);
        player.updateInventory();

        String worldName = section.getString("location.world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) return false;

        Location destination = new Location(world,
                section.getDouble("location.x"), section.getDouble("location.y"), section.getDouble("location.z"),
                (float) section.getDouble("location.yaw"), (float) section.getDouble("location.pitch"));

        // Make sure the destination exists in memory before teleporting. This is especially
        // useful when the admin and legit profiles are far apart or in different worlds.
        world.getChunkAt(destination.getBlockX() >> 4, destination.getBlockZ() >> 4).load();
        return player.teleport(destination);
    }

    static BigDecimal money(ConfigurationSection section) { return number(section, "economy.money"); }
    static BigDecimal ducks(ConfigurationSection section) { return number(section, "economy.ducks"); }

    private static BigDecimal number(ConfigurationSection section, String path) {
        try { return new BigDecimal(section.getString(path, "0")); }
        catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }

    private static void saveItems(ConfigurationSection parent, String path, ItemStack[] items) {
        parent.set(path, null);
        ConfigurationSection sec = parent.createSection(path);
        sec.set("size", items.length);
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item != null && !item.getType().isAir()) sec.set("slots." + i, item);
        }
    }

    private static ItemStack[] loadItems(ConfigurationSection parent, String path, int fallback) {
        ConfigurationSection sec = parent.getConfigurationSection(path);
        if (sec == null) return new ItemStack[fallback];
        ItemStack[] items = new ItemStack[Math.max(0, sec.getInt("size", fallback))];
        ConfigurationSection slots = sec.getConfigurationSection("slots");
        if (slots == null) return items;
        for (String key : slots.getKeys(false)) {
            try {
                int i = Integer.parseInt(key);
                if (i >= 0 && i < items.length) items[i] = slots.getItemStack(key);
            } catch (NumberFormatException ignored) {}
        }
        return items;
    }

    @SuppressWarnings("deprecation")
    private static void addEffect(Player player, String raw) {
        String[] p = raw.split(";", -1);
        if (p.length < 6) return;
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(p[0]);
        if (key == null) return;
        PotionEffectType type = PotionEffectType.getByKey(key);
        if (type == null) return;
        try {
            player.addPotionEffect(new PotionEffect(type, Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Boolean.parseBoolean(p[3]), Boolean.parseBoolean(p[4]), Boolean.parseBoolean(p[5])), true);
        } catch (NumberFormatException ignored) {}
    }

    private static float clamp(float value, float fallback) {
        return Float.isNaN(value) || value < -1F || value > 1F ? fallback : value;
    }
}
