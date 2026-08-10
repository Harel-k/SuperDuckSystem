package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.gui.DuckGui;
import com.qducks.superducksystem.gui.GuiButton;
import com.qducks.superducksystem.gui.GuiItems;
import com.qducks.superducksystem.message.MessageService;
import com.qducks.superducksystem.settings.PlayerSetting;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CrateMenu {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<Integer> DEFAULT_REWARD_SLOTS = java.util.stream.IntStream.range(0, 45).boxed().toList();

    private final SuperDuckSystem plugin;
    private final KeyService keys;
    private final CrateService crates;
    private final MessageService messages;

    public CrateMenu(SuperDuckSystem plugin, KeyService keys, CrateService crates) {
        this.plugin = plugin;
        this.keys = keys;
        this.crates = crates;
        this.messages = new MessageService(plugin);
    }

    public void open(Player player) {
        FileConfiguration config = plugin.configs().crates();
        int rows = clampRows(config.getInt("crate-menu.rows", 6));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("crate-menu.title", "<gold><bold>Crates</bold></gold>")));
        fill(gui, config, "crate-menu.filler");

        int fallbackSlot = 10;
        for (String crateId : crates.configuredCrates()) {
            int slot = config.getInt("crates." + crateId + ".menu-slot", fallbackSlot);
            fallbackSlot += 2;
            if (!validSlot(gui, slot)) {
                continue;
            }
            String keyId = crates.keyId(crateId);
            int balance = keys.cachedKeys(player.getUniqueId(), keyId);
            Material material = material(config.getString("crates." + crateId + ".menu-material"), Material.CHEST);
            String style = crates.openingStyle(crateId);
            ItemStack icon = GuiItems.item(
                    material,
                    "<yellow><bold>" + escape(crates.displayName(crateId)) + "</bold></yellow>",
                    "<gray>Key:</gray> <white>" + escape(keys.keyDisplayName(keyId)) + "</white>",
                    "<gray>You have:</gray> <white>" + balance + "</white>",
                    "<gray>Opening:</gray> <white>" + style + "</white>",
                    "",
                    balance > 0 ? "<green>Click to open!</green>" : "<red>You need a key.</red>"
            );
            gui.set(slot, new GuiButton(icon, context -> openCrate(context.player(), crateId)));
        }

        int previewSlot = config.getInt("crate-menu.info.slot", 49);
        if (validSlot(gui, previewSlot)) {
            gui.setDisplay(previewSlot, GuiItems.item(
                    material(config.getString("crate-menu.info.material"), Material.BOOK),
                    config.getString("crate-menu.info.name", "<aqua><bold>Crate Info</bold></aqua>"),
                    "<gray>Keys are digital and shown in /key.</gray>",
                    "<gray>Loot and opening styles are configurable.</gray>"
            ));
        }
        gui.open(player);
    }

    /**
     * Menu opened by a physical crate block at spawn. It shows the complete loot pool,
     * normalized chances, the player's key balance, and an Open button.
     */
    public void openPhysicalCrate(Player player, String crateId) {
        List<CrateReward> rewards = crates.rewards(crateId);
        FileConfiguration config = plugin.configs().crateLocations();
        int rows = clampRows(config.getInt("block-menu.rows", 6));
        String crateName = crates.displayName(crateId);
        String title = config.getString("block-menu.title", "<gold><bold>%crate%</bold></gold>")
                .replace("%crate%", escape(crateName));
        DuckGui gui = new DuckGui(plugin, rows, MINI.deserialize(title));
        fill(gui, config, "block-menu.filler");

        List<Integer> rewardSlots = config.getIntegerList("block-menu.reward-slots");
        if (rewardSlots.isEmpty()) {
            rewardSlots = DEFAULT_REWARD_SLOTS;
        }
        double totalWeight = rewards.stream().mapToDouble(CrateReward::weight).filter(weight -> weight > 0).sum();
        int index = 0;
        for (CrateReward reward : rewards) {
            if (index >= rewardSlots.size()) {
                break;
            }
            int slot = rewardSlots.get(index++);
            if (!validSlot(gui, slot)) {
                continue;
            }
            ItemStack icon = crates.rewardIcon(reward).clone();
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(MINI.deserialize("<white>" + escape(crates.rewardDescription(reward)) + "</white>"));
            double percent = totalWeight <= 0 ? 0 : (reward.weight() / totalWeight) * 100.0;
            meta.lore(List.of(
                    MINI.deserialize("<gray>Chance:</gray> <yellow>" + formatPercent(percent) + "</yellow>"),
                    MINI.deserialize("<dark_gray>Possible reward from " + escape(crateName) + ".</dark_gray>")
            ));
            icon.setItemMeta(meta);
            gui.setDisplay(slot, icon);
        }

        String keyId = crates.keyId(crateId);
        String keyName = keys.keyDisplayName(keyId);
        int keyBalance = keys.cachedKeys(player.getUniqueId(), keyId);
        Map<String, String> replacements = Map.of(
                "crate", crateName,
                "key", keyName,
                "keys", Integer.toString(keyBalance),
                "open_status", keyBalance > 0 ? "<green>Click to open!</green>" : "<red>You need a key.</red>"
        );

        int keySlot = config.getInt("block-menu.key-info.slot", 46);
        if (validSlot(gui, keySlot)) {
            Material keyMaterial = material(config.getString("block-menu.key-info.material"), Material.TRIPWIRE_HOOK);
            gui.setDisplay(keySlot, configuredItem(config, "block-menu.key-info", keyMaterial,
                    "<yellow><bold>%key%</bold></yellow>", replacements));
        }

        int openSlot = config.getInt("block-menu.open.slot", 49);
        if (validSlot(gui, openSlot)) {
            Material crateMaterial = material(
                    plugin.configs().crates().getString("crates." + crateId + ".menu-material"),
                    Material.CHEST
            );
            ItemStack open = configuredItem(config, "block-menu.open", crateMaterial,
                    "<green><bold>OPEN %crate%</bold></green>", replacements);
            gui.set(openSlot, new GuiButton(open, context -> openCrate(context.player(), crateId)));
        }

        int closeSlot = config.getInt("block-menu.close.slot", 53);
        if (validSlot(gui, closeSlot)) {
            Material closeMaterial = material(config.getString("block-menu.close.material"), Material.BARRIER);
            gui.set(closeSlot, new GuiButton(
                    configuredItem(config, "block-menu.close", closeMaterial, "<red>Close</red>", replacements),
                    context -> context.player().closeInventory()
            ));
        }

        gui.open(player);
    }

    public void openCrate(Player player, String crateId) {
        if (plugin.state().maintenance("crates")) {
            player.sendRichMessage("<red>Crates are temporarily in maintenance mode.</red>");
            return;
        }
        if (keys.cachedKeys(player.getUniqueId(), crates.keyId(crateId)) <= 0) {
            messages.send(player, "crates.no-key", "<red>You do not have the required key.</red>");
            return;
        }
        if (!crates.tryBeginOpen(player.getUniqueId())) {
            messages.send(player, "crates.already-opening", "<yellow>You already have a crate opening in progress.</yellow>");
            return;
        }

        crates.prepareOpen(player, crateId).whenComplete((prepared, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        crates.finishOpen(player.getUniqueId());
                        if (player.isOnline()) {
                            Throwable cause = unwrap(error);
                            if (cause instanceof KeyService.NoKeyException) {
                                messages.send(player, "crates.no-key", "<red>You do not have the required key.</red>");
                            } else if (cause instanceof CrateService.CrateMaintenanceException) {
                                player.sendRichMessage("<red>Crates are temporarily in maintenance mode.</red>");
                            } else {
                                messages.send(player, "crates.open-failed", "<red>Could not open that crate.</red>");
                            }
                        }
                        return;
                    }

                    // The key is consumed and the winner is fixed at this point. Even if the
                    // player disconnects, grant the selected reward instead of allowing a reroll.
                    if (!player.isOnline()) {
                        grantPrepared(player, prepared);
                        return;
                    }
                    if (crates.openingStyle(crateId).equalsIgnoreCase("SCROLL")) {
                        runScroll(player, prepared);
                    } else {
                        grantPrepared(player, prepared);
                    }
                })
        );
    }

    private void runScroll(Player player, CrateService.PreparedOpen prepared) {
        FileConfiguration config = plugin.configs().crates();
        int rows = clampRows(config.getInt("scroll.rows", 3));
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize(config.getString("scroll.title", "<gold><bold>Opening %crate%</bold></gold>")
                        .replace("%crate%", escape(crates.displayName(prepared.crateId())))));
        fill(gui, config, "scroll.filler");

        List<Integer> strip = config.getIntegerList("scroll.slots");
        if (strip.isEmpty()) {
            strip = List.of(9, 10, 11, 12, 13, 14, 15, 16, 17);
        }
        List<Integer> validStrip = strip.stream().filter(slot -> validSlot(gui, slot)).toList();
        if (validStrip.isEmpty()) {
            grantPrepared(player, prepared);
            return;
        }
        int center = validStrip.get(validStrip.size() / 2);
        int totalTicks = Math.max(20, config.getInt("scroll.animation-ticks", 60));
        int period = Math.max(1, config.getInt("scroll.step-ticks", 2));

        gui.open(player);
        List<CrateReward> display = new ArrayList<>();
        for (int i = 0; i < validStrip.size(); i++) {
            display.add(randomReward(prepared.crateId(), prepared.reward()));
        }

        final List<Integer> finalStrip = validStrip;
        new BukkitRunnable() {
            int elapsed;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    // Winner was already selected when the key was consumed. Persist/grant it
                    // rather than refunding the key and making disconnects a reroll mechanic.
                    grantPrepared(player, prepared);
                    return;
                }
                if (player.getOpenInventory().getTopInventory().getHolder() != gui) {
                    // Closing the animation does not let players reroll. Finish the already-selected reward.
                    cancel();
                    grantPrepared(player, prepared);
                    return;
                }

                elapsed += period;
                display.remove(0);
                display.add(elapsed >= totalTicks - (period * 2)
                        ? prepared.reward()
                        : randomReward(prepared.crateId(), prepared.reward()));

                for (int i = 0; i < finalStrip.size(); i++) {
                    int slot = finalStrip.get(i);
                    CrateReward reward = display.get(i);
                    ItemStack icon = rewardIcon(reward, slot == center && elapsed >= totalTicks - (period * 2));
                    gui.setDisplay(slot, icon);
                }

                if (elapsed >= totalTicks) {
                    cancel();
                    gui.setDisplay(center, rewardIcon(prepared.reward(), true));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> grantPrepared(player, prepared), 10L);
                }
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    private CrateReward randomReward(String crateId, CrateReward fallback) {
        CrateReward reward = crates.randomDisplayReward(crateId);
        return reward == null ? fallback : reward;
    }

    private ItemStack rewardIcon(CrateReward reward, boolean winner) {
        ItemStack icon = crates.rewardIcon(reward).clone();
        ItemMeta meta = icon.getItemMeta();
        String prefix = winner ? "<green><bold>▶ WINNER: </bold></green>" : "<white>";
        meta.displayName(MINI.deserialize(prefix + escape(crates.rewardDescription(reward)) + (winner ? "" : "</white>")));
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (winner) {
            lore.add(MINI.deserialize("<yellow>This is your reward!</yellow>"));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private void grantPrepared(Player player, CrateService.PreparedOpen prepared) {
        crates.grant(player, prepared).whenComplete((description, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        crates.refundConsumedKey(player, prepared).whenComplete((ignored, refundError) ->
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    crates.finishOpen(player.getUniqueId());
                                    if (refundError != null) {
                                        plugin.getLogger().severe("Crate reward and key refund both failed for "
                                                + player.getUniqueId() + ": " + unwrap(refundError).getMessage());
                                    }
                                    if (player.isOnline()) {
                                        messages.send(player, "crates.reward-failed",
                                                refundError == null
                                                        ? "<red>The reward failed, so your key was refunded.</red>"
                                                        : "<red>The reward failed. Staff have been notified.</red>");
                                    }
                                })
                        );
                        return;
                    }

                    crates.finishOpen(player.getUniqueId());
                    if (player.isOnline()) {
                        player.closeInventory();
                        messages.send(player, "crates.reward", "<gold>You won <white>%reward%</white> from %crate%!</gold>", Map.of(
                                "reward", description,
                                "crate", crates.displayName(prepared.crateId()),
                                "keys", Integer.toString(prepared.remainingKeys())
                        ));
                    }
                    broadcastWin(player, prepared.crateId(), description);
                })
        );
    }

    private void broadcastWin(Player winner, String crateId, String reward) {
        if (!plugin.configs().crates().getBoolean("crates." + crateId + ".broadcast", false)) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!plugin.settings().get(viewer.getUniqueId(), PlayerSetting.CRATE_BROADCASTS)) {
                continue;
            }
            messages.send(viewer, "crates.broadcast",
                    "<gold><white>%player%</white> won <yellow>%reward%</yellow> from <white>%crate%</white>!</gold>",
                    Map.of(
                            "player", winner.getName(),
                            "reward", reward,
                            "crate", crates.displayName(crateId)
                    ));
        }
    }

    public void preview(Player player, String crateId) {
        List<CrateReward> rewards = crates.rewards(crateId);
        FileConfiguration config = plugin.configs().crates();
        int rows = 6;
        DuckGui gui = new DuckGui(plugin, rows,
                MINI.deserialize("<aqua><bold>" + escape(crates.displayName(crateId)) + " Loot</bold></aqua>"));
        fill(gui, config, "crate-menu.filler");
        int slot = 0;
        for (CrateReward reward : rewards) {
            while (slot < 45 && gui.getInventory().getItem(slot) != null) {
                slot++;
            }
            if (slot >= 45) {
                break;
            }
            ItemStack icon = crates.rewardIcon(reward).clone();
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(MINI.deserialize("<white>" + escape(crates.rewardDescription(reward)) + "</white>"));
            meta.lore(List.of(MINI.deserialize("<gray>Weight:</gray> <white>" + reward.weight() + "</white>")));
            icon.setItemMeta(meta);
            gui.setDisplay(slot++, icon);
        }
        gui.set(49, new GuiButton(GuiItems.item(Material.BARRIER, "<red>Back</red>"), context -> open(context.player())));
        gui.open(player);
    }

    private ItemStack configuredItem(
            FileConfiguration config,
            String base,
            Material fallbackMaterial,
            String fallbackName,
            Map<String, String> replacements
    ) {
        Material iconMaterial = material(config.getString(base + ".material"), fallbackMaterial);
        String name = replace(config.getString(base + ".name", fallbackName), replacements);
        List<String> lore = config.getStringList(base + ".lore").stream()
                .map(line -> replace(line, replacements))
                .toList();
        return GuiItems.item(iconMaterial, name, lore.toArray(String[]::new));
    }

    private String replace(String value, Map<String, String> replacements) {
        String result = value == null ? "" : value;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return result;
    }

    private void fill(DuckGui gui, FileConfiguration config, String base) {
        if (!config.getBoolean(base + ".enabled", true)) {
            return;
        }
        Material fillerMaterial = material(config.getString(base + ".material"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = GuiItems.item(fillerMaterial, config.getString(base + ".name", " "));
        for (int slot = 0; slot < gui.getInventory().getSize(); slot++) {
            gui.setDisplay(slot, filler);
        }
    }

    private Material material(String raw, Material fallback) {
        Material found = raw == null ? null : Material.matchMaterial(raw);
        return found == null ? fallback : found;
    }

    private boolean validSlot(DuckGui gui, int slot) {
        return slot >= 0 && slot < gui.getInventory().getSize();
    }

    private int clampRows(int rows) {
        return Math.max(1, Math.min(6, rows));
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String formatPercent(double value) {
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f%%", value);
        }
        if (value >= 1) {
            return String.format(Locale.ROOT, "%.2f%%", value);
        }
        return String.format(Locale.ROOT, "%.3f%%", value);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<");
    }
}
