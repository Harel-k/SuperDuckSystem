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
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class KeyMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SuperDuckSystem plugin;
    private final KeyService service;
    private final CrateService crates;
    private final CrateMenu crateMenu;

    public KeyMenu(SuperDuckSystem plugin, KeyService service, CrateService crates) {
        this.plugin = plugin;
        this.service = service;
        this.crates = crates;
        this.crateMenu = new CrateMenu(plugin, service, crates);
    }

    public void open(Player player) {
        FileConfiguration config = plugin.configs().crates();
        int rows = Math.max(1, Math.min(6, config.getInt("key-menu.rows", 6)));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("key-menu.title", "<gold><bold>Your Keys</bold></gold>")));
        fill(gui, config);
        render(gui, player);
        gui.open(player);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != gui) {
                    cancel();
                    return;
                }
                render(gui, player);
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void render(DuckGui gui, Player player) {
        FileConfiguration config = plugin.configs().crates();
        KeyService.ProgressSnapshot progress = service.snapshot(player.getUniqueId());
        List<String> ids = new ArrayList<>(service.configuredKeyIds());
        ids.sort(Comparator.comparingInt(id -> config.getInt("keys." + id + ".slot", Integer.MAX_VALUE)));

        for (String rawId : ids) {
            String id = rawId.toLowerCase(Locale.ROOT);
            int slot = config.getInt("keys." + rawId + ".slot", -1);
            if (slot < 0 || slot >= gui.getInventory().getSize()) {
                continue;
            }
            Material material = material(config.getString("keys." + rawId + ".material"), Material.TRIPWIRE_HOOK);
            String name = config.getString("keys." + rawId + ".name", "<yellow><bold>" + service.keyDisplayName(id) + "</bold></yellow>");
            int amount = service.cachedKeys(player.getUniqueId(), id);

            List<String> lore = new ArrayList<>();
            List<String> configuredLore = config.getStringList("keys." + rawId + ".lore");
            if (configuredLore.isEmpty()) {
                configuredLore = List.of(
                        "<gray>Keys:</gray> <white>%amount%</white>",
                        "",
                        "%progress%"
                );
            }

            String progressLine = "<dark_gray>No active timer for this key.</dark_gray>";
            if (!progress.loaded()) {
                progressLine = "<yellow>Loading playtime progress...</yellow>";
            } else if (id.equals(progress.nextKeyId())) {
                progressLine = progress.repeating()
                        ? "<green>Repeat reward in:</green> <white>" + formatTime(progress.secondsRemaining()) + "</white>"
                        : "<green>Next milestone in:</green> <white>" + formatTime(progress.secondsRemaining()) + "</white>";
            }

            for (String line : configuredLore) {
                lore.add(line
                        .replace("%amount%", Integer.toString(amount))
                        .replace("%progress%", progressLine)
                        .replace("%timer%", progress.loaded() && id.equals(progress.nextKeyId())
                                ? formatTime(progress.secondsRemaining())
                                : "-")
                        .replace("%key%", service.keyDisplayName(id)));
            }

            String crateId = crateForKey(id);
            if (crateId != null) {
                lore.add("");
                lore.add(amount > 0 ? "<green>Click to open the crate.</green>" : "<dark_gray>Earn a key to open this crate.</dark_gray>");
                ItemStack icon = GuiItems.item(material, name, lore.toArray(String[]::new));
                gui.set(slot, new GuiButton(icon, context -> crateMenu.openCrate(context.player(), crateId)));
            } else {
                gui.setDisplay(slot, GuiItems.item(material, name, lore.toArray(String[]::new)));
            }
        }

        int progressSlot = config.getInt("key-menu.progress.slot", 49);
        if (progressSlot >= 0 && progressSlot < gui.getInventory().getSize()) {
            Material progressMaterial = material(config.getString("key-menu.progress.material"), Material.CLOCK);
            String progressName = config.getString("key-menu.progress.name", "<aqua><bold>Playtime Key Progress</bold></aqua>");
            List<String> lore = new ArrayList<>();
            if (!progress.loaded()) {
                lore.add("<yellow>Loading...</yellow>");
            } else {
                String display = service.keyDisplayName(progress.nextKeyId());
                lore.add("<gray>Next:</gray> <white>" + display + "</white>");
                lore.add("<gray>Time left:</gray> <white>" + formatTime(progress.secondsRemaining()) + "</white>");
                if (progress.repeating()) {
                    lore.add("");
                    lore.add("<green>All milestones complete!</green>");
                    lore.add("<gray>You now earn the repeat key while staying online.</gray>");
                } else {
                    lore.add("<gray>Milestone:</gray> <white>" + (progress.milestoneIndex() + 1) + "/" + progress.milestoneCount() + "</white>");
                    lore.add("");
                    lore.add("<yellow>Leaving resets only the current milestone timer.</yellow>");
                }
            }
            gui.setDisplay(progressSlot, GuiItems.item(progressMaterial, progressName, lore.toArray(String[]::new)));
        }
    }

    private String crateForKey(String keyId) {
        for (String crateId : crates.configuredCrates()) {
            if (crates.keyId(crateId).equalsIgnoreCase(keyId)) {
                return crateId;
            }
        }
        return null;
    }

    private void fill(DuckGui gui, FileConfiguration config) {
        if (!config.getBoolean("key-menu.filler.enabled", true)) {
            return;
        }
        Material material = material(config.getString("key-menu.filler.material"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = GuiItems.item(material, config.getString("key-menu.filler.name", " "));
        for (int slot = 0; slot < gui.getInventory().getSize(); slot++) {
            gui.setDisplay(slot, filler);
        }
    }

    private Material material(String raw, Material fallback) {
        Material found = raw == null ? null : Material.matchMaterial(raw);
        return found == null ? fallback : found;
    }

    private String formatTime(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3600L;
        long minutes = (safe % 3600L) / 60L;
        long secs = safe % 60L;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%02dm %02ds", minutes, secs);
    }
}
