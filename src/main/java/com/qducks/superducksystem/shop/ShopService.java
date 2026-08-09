package com.qducks.superducksystem.shop;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;

public final class ShopService {
    private final SuperDuckSystem plugin;

    public ShopService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public BigDecimal sellUnitPrice(ItemStack item) {
        if (!isSellable(item)) {
            return BigDecimal.ZERO;
        }
        String raw = plugin.configs().shop().getString("sell.prices." + item.getType().name());
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal price = new BigDecimal(raw);
            return price.signum() > 0 ? price : BigDecimal.ZERO;
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("Invalid sell price for " + item.getType().name() + ": " + raw);
            return BigDecimal.ZERO;
        }
    }

    public BigDecimal stackSellValue(ItemStack item) {
        return sellUnitPrice(item).multiply(BigDecimal.valueOf(item == null ? 0 : item.getAmount()));
    }

    public boolean isSellable(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return false;
        }
        if (plugin.configs().shop().getBoolean("sell.safety.reject-superduck-items", true)
                && plugin.customItems().getId(item) != null) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (plugin.configs().shop().getBoolean("sell.safety.reject-renamed-items", true) && meta.hasDisplayName()) {
                return false;
            }
            if (plugin.configs().shop().getBoolean("sell.safety.reject-lore-items", true) && meta.hasLore()) {
                return false;
            }
            if (plugin.configs().shop().getBoolean("sell.safety.reject-enchanted-items", true) && meta.hasEnchants()) {
                return false;
            }
            if (plugin.configs().shop().getBoolean("sell.safety.reject-damaged-items", true)
                    && meta instanceof Damageable damageable && damageable.hasDamage()) {
                return false;
            }
        }

        if (plugin.configs().shop().getBoolean("sell.safety.reject-shulker-boxes", true)
                && item.getType().name().endsWith("_SHULKER_BOX")) {
            return false;
        }
        return plugin.configs().shop().contains("sell.prices." + item.getType().name());
    }
}
