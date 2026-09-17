package com.qducks.superducksystem.adminmode;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.TransactionType;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AdminModeService {
    public static final String TOGGLE_PERMISSION = "superduck.abuse.toggle";
    public static final String OTHERS_PERMISSION = "superduck.abuse.others";
    public static final String TAG = "sds_abuse_mode";

    private final SuperDuckSystem plugin;
    private final File dataFolder;
    private final Set<UUID> active = new HashSet<>();
    private final Set<UUID> switching = new HashSet<>();
    private final Map<UUID, PermissionAttachment> bypassAttachments = new HashMap<>();
    private File configFile;
    private FileConfiguration config;

    public AdminModeService(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "abuse-data");
    }

    public void start() {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create abuse-data folder.");
        }
        configFile = new File(plugin.getDataFolder(), "abuse.yml");
        if (!configFile.exists()) plugin.saveResource("abuse.yml", false);
        reload();
        for (Player player : Bukkit.getOnlinePlayers()) handleJoin(player);
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(configFile);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isActive(player)) installBypasses(player);
            else removeBypasses(player);
        }
    }

    public void stop() {
        for (Player player : Bukkit.getOnlinePlayers()) saveCurrentProfile(player);
        for (UUID uuid : Set.copyOf(bypassAttachments.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) removeBypasses(player);
        }
        active.clear();
        switching.clear();
    }

    public boolean isActive(Player player) {
        return active.contains(player.getUniqueId());
    }

    public boolean isSwitching(Player player) {
        return switching.contains(player.getUniqueId());
    }

    public boolean isManaged(Player player) {
        return player.hasPermission(TOGGLE_PERMISSION) || profileFile(player.getUniqueId()).exists();
    }

    public void handleJoin(Player player) {
        YamlConfiguration data = loadData(player.getUniqueId());
        String transition = data.getString("transition", "none");
        boolean storedActive = data.getBoolean("active", false);

        if (transition.equalsIgnoreCase("enabling")) {
            // A crash/restart happened mid-enable. Roll back to legit so no admin state can leak into legit mode.
            recoverTransition(player, data, "legit", false);
            return;
        }
        if (transition.equalsIgnoreCase("disabling")) {
            // A crash/restart happened mid-disable. Roll back to admin mode, then the player can disable it again safely.
            recoverTransition(player, data, "admin", true);
            return;
        }

        if (storedActive) {
            active.add(player.getUniqueId());
            player.addScoreboardTag(TAG);
            installBypasses(player);
        } else {
            active.remove(player.getUniqueId());
            player.removeScoreboardTag(TAG);
            removeBypasses(player);
            if (isManaged(player)) enforceLegitSafety(player);
        }
    }

    public void handleQuit(Player player) {
        saveCurrentProfile(player);
        removeBypasses(player);
        switching.remove(player.getUniqueId());
    }

    public void enable(Player target, CommandSender actor) {
        if (isSwitching(target)) {
            actor.sendMessage("§cThat player is already switching profiles.");
            return;
        }
        if (isActive(target)) {
            actor.sendMessage("§e" + target.getName() + " is already in Abuse Mode.");
            return;
        }

        switching.add(target.getUniqueId());
        fetchBalances(target).whenComplete((balances, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !target.isOnline()) {
                switching.remove(target.getUniqueId());
                actor.sendMessage("§cCould not read " + target.getName() + "'s economy state. Abuse Mode was not changed.");
                if (error != null) plugin.getLogger().warning("Failed to read balances for Abuse Mode: " + error.getMessage());
                return;
            }

            try {
                YamlConfiguration data = loadData(target.getUniqueId());
                ConfigurationSection legit = resetSection(data, "profiles.legit");
                PlayerStateCodec.capture(target, legit, balances.money, balances.ducks);
                // Legit Mode is always survival, even if the owner happened to be creative
                // before switching profiles for the first time.
                legit.set("gamemode", GameMode.SURVIVAL.name());

                ConfigurationSection admin = data.getConfigurationSection("profiles.admin");
                if (admin == null) {
                    admin = resetSection(data, "profiles.admin");
                    PlayerStateCodec.createFreshAdmin(target, admin, balances.money, balances.ducks, defaultAdminGameMode());
                }

                data.set("last-name", target.getName());
                data.set("transition", "enabling");
                saveData(target.getUniqueId(), data);

                active.add(target.getUniqueId());
                installBypasses(target);
                boolean teleported = PlayerStateCodec.apply(target, admin);
                target.addScoreboardTag(TAG);
                restoreBalances(target, admin).whenComplete((ignored, restoreError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (restoreError != null) {
                        plugin.getLogger().severe("Failed to restore admin economy profile for " + target.getName() + ": " + restoreError.getMessage());
                        actor.sendMessage("§cAbuse Mode switched player state, but economy restore failed. Check console before continuing.");
                        switching.remove(target.getUniqueId());
                        return;
                    }
                    YamlConfiguration finished = loadData(target.getUniqueId());
                    finished.set("active", true);
                    finished.set("transition", "none");
                    saveData(target.getUniqueId(), finished);
                    switching.remove(target.getUniqueId());
                    target.sendTitle("§c§lABUSE MODE", "§7Admin profile loaded", 10, 50, 10);
                    target.sendMessage("§c§lABUSE MODE §8» §fON §7— admin inventory, ender chest, location and economy loaded.");
                    if (!teleported) target.sendMessage("§eYour admin profile world could not be restored, so your current location was kept.");
                    if (!actor.equals(target)) actor.sendMessage("§aEnabled Abuse Mode for §f" + target.getName() + "§a.");
                }));
            } catch (Exception exception) {
                switching.remove(target.getUniqueId());
                active.remove(target.getUniqueId());
                removeBypasses(target);
                plugin.getLogger().severe("Failed to enable Abuse Mode for " + target.getName() + ": " + exception.getMessage());
                actor.sendMessage("§cFailed to enable Abuse Mode. Check console.");
            }
        }));
    }

    public void disable(Player target, CommandSender actor) {
        if (isSwitching(target)) {
            actor.sendMessage("§cThat player is already switching profiles.");
            return;
        }
        if (!isActive(target)) {
            enforceLegitSafety(target);
            actor.sendMessage("§e" + target.getName() + " is already in Legit Mode. Survival and WorldGuard protection were enforced.");
            return;
        }

        switching.add(target.getUniqueId());
        fetchBalances(target).whenComplete((balances, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !target.isOnline()) {
                switching.remove(target.getUniqueId());
                actor.sendMessage("§cCould not read " + target.getName() + "'s economy state. Abuse Mode was not changed.");
                return;
            }

            try {
                YamlConfiguration data = loadData(target.getUniqueId());
                ConfigurationSection legit = data.getConfigurationSection("profiles.legit");
                if (legit == null) {
                    switching.remove(target.getUniqueId());
                    actor.sendMessage("§cThe legit profile is missing. Refusing to disable Abuse Mode so admin items cannot leak.");
                    return;
                }

                ConfigurationSection admin = resetSection(data, "profiles.admin");
                PlayerStateCodec.capture(target, admin, balances.money, balances.ducks);
                legit.set("gamemode", GameMode.SURVIVAL.name());
                data.set("last-name", target.getName());
                data.set("active", true);
                data.set("transition", "disabling");
                saveData(target.getUniqueId(), data);

                boolean teleported = PlayerStateCodec.apply(target, legit);
                enforceLegitSafety(target);
                restoreBalances(target, legit).whenComplete((ignored, restoreError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (restoreError != null) {
                        plugin.getLogger().severe("Failed to restore legit economy profile for " + target.getName() + ": " + restoreError.getMessage());
                        actor.sendMessage("§cLegit player state restored, but economy restore failed. Check console before continuing.");
                        switching.remove(target.getUniqueId());
                        return;
                    }

                    YamlConfiguration finished = loadData(target.getUniqueId());
                    finished.set("active", false);
                    finished.set("transition", "none");
                    saveData(target.getUniqueId(), finished);
                    active.remove(target.getUniqueId());
                    target.removeScoreboardTag(TAG);
                    removeBypasses(target);
                    switching.remove(target.getUniqueId());
                    target.sendTitle("§a§lLEGIT MODE", "§7Survival profile restored", 10, 50, 10);
                    target.sendMessage("§a§lLEGIT MODE §8» §fON §7— survival, WorldGuard protection, legit inventory, ender chest, location and economy restored.");
                    if (!teleported) target.sendMessage("§eYour legit profile world could not be restored, so your current location was kept.");
                    if (!actor.equals(target)) actor.sendMessage("§aDisabled Abuse Mode for §f" + target.getName() + "§a.");
                }));
            } catch (Exception exception) {
                switching.remove(target.getUniqueId());
                plugin.getLogger().severe("Failed to disable Abuse Mode for " + target.getName() + ": " + exception.getMessage());
                actor.sendMessage("§cFailed to disable Abuse Mode. Check console.");
            }
        }));
    }

    public String modeName(Player player) {
        return isActive(player) ? "ABUSE" : "LEGIT";
    }

    public boolean isLegitBlockedCommand(String message) {
        return matchesCommandList(message, config.getStringList("legit-mode.blocked-commands"));
    }

    public boolean isAdminTransferCommand(String message) {
        return matchesCommandList(message, config.getStringList("abuse-mode.blocked-transfer-commands"));
    }

    public boolean preventWorldItemTransfer() {
        return config.getBoolean("abuse-mode.prevent-world-item-transfer", true);
    }

    public String blockedMessage() {
        return color(config.getString("messages.legit-command-blocked", "&c&lLEGIT MODE &8» &7Enable &f/abuse on &7to use admin commands."));
    }

    public String transferBlockedMessage() {
        return color(config.getString("messages.abuse-transfer-blocked", "&c&lABUSE MODE &8» &7That command could move admin resources into the legit economy."));
    }

    public File profileFileForDebug(UUID uuid) {
        return profileFile(uuid);
    }

    private void recoverTransition(Player player, YamlConfiguration data, String profileName, boolean recoveredActive) {
        switching.add(player.getUniqueId());
        ConfigurationSection section = data.getConfigurationSection("profiles." + profileName);
        if (section == null) {
            switching.remove(player.getUniqueId());
            plugin.getLogger().severe("Cannot recover Abuse Mode transition for " + player.getName() + ": profile " + profileName + " is missing.");
            return;
        }

        if (recoveredActive) {
            active.add(player.getUniqueId());
            installBypasses(player);
            player.addScoreboardTag(TAG);
        } else {
            active.remove(player.getUniqueId());
            removeBypasses(player);
            player.removeScoreboardTag(TAG);
            section.set("gamemode", GameMode.SURVIVAL.name());
        }

        PlayerStateCodec.apply(player, section);
        if (!recoveredActive) enforceLegitSafety(player);
        restoreBalances(player, section).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().severe("Failed Abuse Mode crash recovery economy restore for " + player.getName() + ": " + error.getMessage());
                switching.remove(player.getUniqueId());
                return;
            }
            YamlConfiguration recovered = loadData(player.getUniqueId());
            recovered.set("active", recoveredActive);
            recovered.set("transition", "none");
            if (!recoveredActive) recovered.set("profiles.legit.gamemode", GameMode.SURVIVAL.name());
            saveData(player.getUniqueId(), recovered);
            switching.remove(player.getUniqueId());
            player.sendMessage("§e§lABUSE SAFETY §8» §7A previous profile switch was interrupted. Your safe " + profileName + " profile was recovered.");
        }));
    }

    private void enforceLegitSafety(Player player) {
        UUID uuid = player.getUniqueId();
        boolean alreadySwitching = switching.contains(uuid);
        if (!alreadySwitching) switching.add(uuid);
        try {
            if (player.getGameMode() != GameMode.SURVIVAL) player.setGameMode(GameMode.SURVIVAL);

            // /rg bypass is a toggle. Using WorldGuard's session API lets us guarantee
            // bypass is OFF instead of accidentally toggling it on.
            if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
                LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
                WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer).setBypassDisabled(true);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not force WorldGuard bypass off for " + player.getName() + ": " + throwable.getMessage());
        } finally {
            if (!alreadySwitching) switching.remove(uuid);
        }
    }

    private void saveCurrentProfile(Player player) {
        File file = profileFile(player.getUniqueId());
        if (!file.exists()) return;
        YamlConfiguration data = loadData(player.getUniqueId());
        if (!data.getString("transition", "none").equalsIgnoreCase("none")) return;
        String profile = isActive(player) ? "admin" : "legit";
        ConfigurationSection section = resetSection(data, "profiles." + profile);
        PlayerStateCodec.capture(player, section,
                plugin.economy().cachedBalance(player.getUniqueId(), CurrencyType.MONEY),
                plugin.economy().cachedBalance(player.getUniqueId(), CurrencyType.DUCKS));
        if (!isActive(player)) section.set("gamemode", GameMode.SURVIVAL.name());
        data.set("last-name", player.getName());
        data.set("active", isActive(player));
        saveData(player.getUniqueId(), data);
    }

    private CompletableFuture<Balances> fetchBalances(Player player) {
        CompletableFuture<BigDecimal> money = plugin.economy().balance(player.getUniqueId(), CurrencyType.MONEY);
        CompletableFuture<BigDecimal> ducks = plugin.economy().balance(player.getUniqueId(), CurrencyType.DUCKS);
        return money.thenCombine(ducks, Balances::new);
    }

    private CompletableFuture<Void> restoreBalances(Player player, ConfigurationSection section) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<?> money = plugin.economy().set(uuid, CurrencyType.MONEY,
                PlayerStateCodec.money(section), TransactionType.ADMIN_SET, uuid);
        CompletableFuture<?> ducks = plugin.economy().set(uuid, CurrencyType.DUCKS,
                PlayerStateCodec.ducks(section), TransactionType.ADMIN_SET, uuid);
        return CompletableFuture.allOf(money, ducks);
    }

    private void installBypasses(Player player) {
        removeBypasses(player);
        PermissionAttachment attachment = player.addAttachment(plugin);
        for (String permission : config.getStringList("abuse-mode.bypass-permissions")) {
            if (!permission.isBlank()) attachment.setPermission(permission, true);
        }
        player.recalculatePermissions();
        bypassAttachments.put(player.getUniqueId(), attachment);
    }

    private void removeBypasses(Player player) {
        PermissionAttachment attachment = bypassAttachments.remove(player.getUniqueId());
        if (attachment != null) {
            try { player.removeAttachment(attachment); }
            catch (IllegalArgumentException ignored) {}
            player.recalculatePermissions();
        }
    }

    private boolean matchesCommandList(String message, java.util.List<String> rules) {
        String body = message.startsWith("/") ? message.substring(1) : message;
        String lower = body.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;
        String root = lower.split("\\s+", 2)[0];
        String unnamespaced = root.contains(":") ? root.substring(root.indexOf(':') + 1) : root;
        for (String rawRule : rules) {
            String rule = rawRule.trim().toLowerCase(Locale.ROOT);
            if (rule.isEmpty()) continue;
            if (rule.equals("//") && message.startsWith("//")) return true;
            if (root.equals(rule) || unnamespaced.equals(rule)) return true;
        }
        return false;
    }

    private GameMode defaultAdminGameMode() {
        try { return GameMode.valueOf(config.getString("abuse-mode.default-gamemode", "CREATIVE").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return GameMode.CREATIVE; }
    }

    private ConfigurationSection resetSection(YamlConfiguration data, String path) {
        data.set(path, null);
        return data.createSection(path);
    }

    private YamlConfiguration loadData(UUID uuid) {
        return YamlConfiguration.loadConfiguration(profileFile(uuid));
    }

    private void saveData(UUID uuid, YamlConfiguration data) {
        try { data.save(profileFile(uuid)); }
        catch (IOException exception) { throw new IllegalStateException("Failed to save Abuse Mode profile for " + uuid, exception); }
    }

    private File profileFile(UUID uuid) {
        return new File(dataFolder, uuid + ".yml");
    }

    private static String color(String text) {
        return text.replace('&', '§');
    }

    private record Balances(BigDecimal money, BigDecimal ducks) {}
}
