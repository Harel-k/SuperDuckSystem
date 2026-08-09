package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.gui.DuckGui;
import com.qducks.superducksystem.gui.GuiButton;
import com.qducks.superducksystem.gui.GuiItems;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class CratePreviewMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SuperDuckSystem plugin;
    private final CrateService crates;
    private final CrateMenu crateMenu;

    public CratePreviewMenu(SuperDuckSystem plugin, KeyService keys, CrateService crates) {
        this.plugin = plugin;
        this.crates = crates;
        this.crateMenu = new CrateMenu(plugin, keys, crates);
    }

    public void open(Player player, String crateId) {
        FileConfiguration config = plugin.configs().crates();
        DuckGui gui = new DuckGui(plugin, 6,
                MINI.deserialize("<aqua><bold>" + escape(crates.displayName(crateId)) + " Loot</bold></aqua>"));
        fill(gui, config);

        int slot = 0;
        for (CrateReward reward : crates.rewards(crateId)) {
            if (slot >= 45) {
                break;
            }
            ItemStack icon = crates.rewardIcon(reward).clone();
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(MINI.deserialize("<white>" + escape(crates.rewardDescription(reward)) + "</white>"));
            meta.lore(List.of(
                    MINI.deserialize("<gray>Relative weight:</gray> <white>" + reward.weight() + "</white>"),
                    MINI.deserialize("<dark_gray>Higher weight = more likely.</dark_gray>")
            ));
            icon.setItemMeta(meta);
            gui.setDisplay(slot++, icon);
        }

        gui.set(49, new GuiButton(GuiItems.item(Material.BARRIER, "<red>Back</red>"),
                context -> crateMenu.open(context.player())));
        gui.open(player);
    }

    private void fill(DuckGui gui, FileConfiguration config) {
        if (!config.getBoolean("crate-menu.filler.enabled", true)) {
            return;
        }
        Material material = Material.matchMaterial(config.getString("crate-menu.filler.material", "BLACK_STAINED_GLASS_PANE"));
        if (material == null) {
            material = Material.BLACK_STAINED_GLASS_PANE;
        }
        ItemStack filler = GuiItems.item(material, config.getString("crate-menu.filler.name", " "));
        for (int slot = 0; slot < gui.getInventory().getSize(); slot++) {
            gui.setDisplay(slot, filler);
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<");
    }
}
