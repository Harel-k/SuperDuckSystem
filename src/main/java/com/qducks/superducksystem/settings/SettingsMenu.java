package com.qducks.superducksystem.settings;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.gui.DuckGui;
import com.qducks.superducksystem.gui.GuiButton;
import com.qducks.superducksystem.gui.GuiItems;
import com.qducks.superducksystem.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class SettingsMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SuperDuckSystem plugin;
    private final MessageService messages;

    public SettingsMenu(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.messages = new MessageService(plugin);
    }

    public void open(Player player) {
        if (plugin.integrations().bedrock().isBedrock(player)
                && plugin.integrations().bedrock().openSettings(player)) {
            return;
        }
        openJava(player);
    }

    public void openJava(Player player) {
        FileConfiguration config = plugin.configs().settings();
        int rows = Math.max(1, Math.min(6, config.getInt("menu.rows", 6)));
        String rawTitle = config.getString("menu.title", "<aqua><bold>Player Settings</bold></aqua>");
        Component title = MINI.deserialize(replaceCommon(rawTitle));
        DuckGui gui = new DuckGui(plugin, rows, title);

        if (config.getBoolean("menu.filler.enabled", true)) {
            Material fillerMaterial = material(config.getString("menu.filler.material"), Material.BLACK_STAINED_GLASS_PANE);
            String fillerName = config.getString("menu.filler.name", " ");
            ItemStack filler = GuiItems.item(fillerMaterial, replaceCommon(fillerName));
            for (int slot = 0; slot < rows * 9; slot++) {
                gui.setDisplay(slot, filler);
            }
        }

        for (PlayerSetting setting : PlayerSetting.values()) {
            String base = "menu.items." + setting.configKey();
            if (!config.getBoolean(base + ".enabled", true)) {
                continue;
            }
            int slot = config.getInt(base + ".slot", -1);
            if (slot < 0 || slot >= rows * 9) {
                plugin.getLogger().warning("Invalid settings GUI slot for " + setting.configKey() + ": " + slot);
                continue;
            }

            boolean enabled = plugin.settings().get(player.getUniqueId(), setting);
            Material fallback = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
            Material itemMaterial = material(config.getString(base + ".material"), fallback);
            String name = replace(config.getString(base + ".name", "<white>" + setting.configKey() + "</white>"), enabled);
            List<String> lore = new ArrayList<>();
            for (String line : config.getStringList(base + ".lore")) {
                lore.add(replace(line, enabled));
            }
            if (lore.isEmpty()) {
                lore.add(replace("%status%", enabled));
                lore.add(replace("<yellow>Click to toggle.</yellow>", enabled));
            }

            ItemStack icon = GuiItems.item(itemMaterial, name, lore.toArray(String[]::new));
            gui.set(slot, new GuiButton(icon, context -> toggle(context.player(), setting)));
        }

        int closeSlot = config.getInt("menu.close.slot", rows * 9 - 5);
        if (closeSlot >= 0 && closeSlot < rows * 9 && config.getBoolean("menu.close.enabled", true)) {
            Material closeMaterial = material(config.getString("menu.close.material"), Material.BARRIER);
            ItemStack close = GuiItems.item(
                    closeMaterial,
                    replaceCommon(config.getString("menu.close.name", "<red>Close</red>")),
                    config.getStringList("menu.close.lore").stream().map(this::replaceCommon).toArray(String[]::new)
            );
            gui.set(closeSlot, new GuiButton(close, context -> context.player().closeInventory()));
        }

        gui.open(player);
    }

    private void toggle(Player player, PlayerSetting setting) {
        plugin.settings().toggle(player.getUniqueId(), setting).whenComplete((value, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages.send(player, "settings.failed", "<red>Could not save that setting.</red>");
                        return;
                    }
                    openJava(player);
                })
        );
    }

    private String replace(String text, boolean enabled) {
        FileConfiguration config = plugin.configs().settings();
        String enabledText = config.getString("menu.status.enabled", "<green>ENABLED ✔</green>");
        String disabledText = config.getString("menu.status.disabled", "<red>DISABLED ✖</red>");
        return replaceCommon(text)
                .replace("%status%", enabled ? enabledText : disabledText)
                .replace("%enabled%", Boolean.toString(enabled));
    }

    private String replaceCommon(String text) {
        return text.replace("%server_name%", plugin.configs().serverName());
    }

    private Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material found = Material.matchMaterial(raw);
        return found == null ? fallback : found;
    }
}
