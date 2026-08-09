package com.qducks.superducksystem.item;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class CustomItemService {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SuperDuckSystem plugin;
    private final NamespacedKey itemIdKey;

    public CustomItemService(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.itemIdKey = new NamespacedKey(plugin, "item_id");
    }

    public ItemStack tag(ItemStack item, String itemId) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, normalize(itemId));
        copy.setItemMeta(meta);
        return copy;
    }

    public @Nullable String getId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    }

    public boolean is(ItemStack item, String itemId) {
        String current = getId(item);
        return current != null && current.equalsIgnoreCase(itemId);
    }

    public boolean exists(String itemId) {
        return plugin.configs().customItems().isConfigurationSection("items." + normalize(itemId));
    }

    public @Nullable ItemStack createConfigured(String requestedId, int amount) {
        String itemId = normalize(requestedId);
        ConfigurationSection section = plugin.configs().customItems().getConfigurationSection("items." + itemId);
        if (section == null) {
            return null;
        }

        Material material = Material.matchMaterial(section.getString("material", "STONE"));
        if (material == null || material.isAir() || !material.isItem()) {
            plugin.getLogger().warning("Invalid material for custom item " + itemId);
            return null;
        }

        ItemStack item = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        String name = section.getString("name");
        if (name != null && !name.isBlank()) {
            meta.displayName(MINI.deserialize(name));
        }
        List<String> lore = section.getStringList("lore");
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(MINI::deserialize).toList());
        }
        meta.setUnbreakable(section.getBoolean("unbreakable", false));

        ConfigurationSection enchantments = section.getConfigurationSection("enchantments");
        if (enchantments != null) {
            for (String rawKey : enchantments.getKeys(false)) {
                NamespacedKey key = NamespacedKey.minecraft(rawKey.toLowerCase(Locale.ROOT));
                Enchantment enchantment = Registry.ENCHANTMENT.get(key);
                if (enchantment == null) {
                    plugin.getLogger().warning("Unknown enchantment '" + rawKey + "' for custom item " + itemId);
                    continue;
                }
                int level = Math.max(1, enchantments.getInt(rawKey, 1));
                meta.addEnchant(enchantment, level, true);
            }
        }

        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, itemId);
        item.setItemMeta(meta);
        return item;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
