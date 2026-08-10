package com.qducks.superducksystem.crate;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.economy.CurrencyType;
import com.qducks.superducksystem.economy.TransactionType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class CrateService {
    private final SuperDuckSystem plugin;
    private final KeyService keys;

    public CrateService(SuperDuckSystem plugin, KeyService keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    public List<String> configuredCrates() {
        FileConfiguration config = plugin.configs().crates();
        if (!config.isConfigurationSection("crates")) return List.of();
        return new ArrayList<>(config.getConfigurationSection("crates").getKeys(false));
    }

    public String displayName(String crateId) {
        String id = normalize(crateId);
        return plugin.configs().crates().getString("crates." + id + ".display-name", pretty(id) + " Crate");
    }

    public String keyId(String crateId) {
        String id = normalize(crateId);
        return normalize(plugin.configs().crates().getString("crates." + id + ".key", id));
    }

    public String openingStyle(String crateId) {
        return plugin.configs().crates().getString("crates." + normalize(crateId) + ".opening-style", "QUICK").trim().toUpperCase(Locale.ROOT);
    }

    public List<CrateReward> rewards(String crateId) {
        String id = normalize(crateId);
        List<CrateReward> rewards = new ArrayList<>();
        for (Map<?, ?> map : plugin.configs().crates().getMapList("crates." + id + ".loot")) {
            CrateReward parsed = parseReward(map);
            if (parsed != null && parsed.weight() > 0) rewards.add(parsed);
        }
        return List.copyOf(rewards);
    }

    public CompletableFuture<PreparedOpen> prepareOpen(Player player, String requestedCrateId) {
        if (plugin.state().maintenance("crates")) return CompletableFuture.failedFuture(new CrateMaintenanceException());
        String crateId = normalize(requestedCrateId);
        if (!configuredCrates().stream().map(this::normalize).toList().contains(crateId)) return CompletableFuture.failedFuture(new UnknownCrateException(crateId));
        List<CrateReward> rewards = rewards(crateId);
        if (rewards.isEmpty()) return CompletableFuture.failedFuture(new EmptyCrateException(crateId));
        CrateReward reward = select(rewards);
        String keyId = keyId(crateId);
        return keys.consumeKey(player.getUniqueId(), keyId).thenApply(remainingKeys -> new PreparedOpen(crateId, keyId, remainingKeys, reward));
    }

    public CompletableFuture<String> grant(Player player, PreparedOpen prepared) {
        CrateReward reward = prepared.reward();
        CompletableFuture<String> grant = switch (reward.type()) {
            case ITEM -> {
                int amount = Math.max(1, reward.itemAmount());
                yield queueItemReward(player, new ItemStack(reward.material()), amount,
                        amount + "x " + pretty(reward.material().name()), "CRATE_ITEM");
            }
            case CUSTOM_ITEM -> {
                int amount = Math.max(1, reward.itemAmount());
                ItemStack template = plugin.customItems().createConfigured(reward.customItemId(), 1);
                if (template == null) yield CompletableFuture.failedFuture(new IllegalArgumentException("Unknown custom crate item: " + reward.customItemId()));
                template.setAmount(1);
                yield queueItemReward(player, template, amount,
                        amount + "x " + pretty(reward.customItemId()), "CRATE_CUSTOM_ITEM");
            }
            case MONEY -> plugin.economy().add(player.getUniqueId(), CurrencyType.MONEY, reward.currencyAmount(), TransactionType.CRATE_REWARD, null)
                    .thenApply(ignored -> plugin.economy().formatter().format(CurrencyType.MONEY, reward.currencyAmount()));
            case DUCKS -> plugin.economy().add(player.getUniqueId(), CurrencyType.DUCKS, reward.currencyAmount(), TransactionType.CRATE_REWARD, null)
                    .thenApply(ignored -> plugin.economy().formatter().format(CurrencyType.DUCKS, reward.currencyAmount()));
            case KEY -> keys.giveKeys(player.getUniqueId(), reward.keyId(), reward.keyAmount())
                    .thenApply(ignored -> reward.keyAmount() + "x " + keys.keyDisplayName(reward.keyId()));
        };
        return grant.thenCompose(description -> plugin.stats().incrementCratesOpened(player.getUniqueId())
                .exceptionally(error -> {
                    plugin.getLogger().warning("Could not record crate stats for " + player.getUniqueId() + ": " + error.getMessage());
                    return null;
                }).thenApply(ignored -> description));
    }

    public CompletableFuture<Void> refundConsumedKey(Player player, PreparedOpen prepared) {
        return keys.giveKeys(player.getUniqueId(), prepared.keyId(), 1).thenApply(ignored -> null);
    }

    public CrateReward randomDisplayReward(String crateId) {
        List<CrateReward> rewards = rewards(crateId);
        return rewards.isEmpty() ? null : select(rewards);
    }

    private CrateReward select(List<CrateReward> rewards) {
        double total = rewards.stream().mapToDouble(CrateReward::weight).filter(weight -> weight > 0).sum();
        if (total <= 0) throw new IllegalArgumentException("Crate loot has no positive weights");
        double roll = ThreadLocalRandom.current().nextDouble(total);
        double cursor = 0;
        for (CrateReward reward : rewards) {
            cursor += Math.max(0, reward.weight());
            if (roll < cursor) return reward;
        }
        return rewards.get(rewards.size() - 1);
    }

    private CrateReward parseReward(Map<?, ?> map) {
        try {
            String typeRaw = string(map.get("type"), "").toUpperCase(Locale.ROOT);
            CrateReward.Type type = CrateReward.Type.valueOf(typeRaw);
            double weight = decimal(map.get("weight"), "0").doubleValue();
            return switch (type) {
                case ITEM -> {
                    Material material = Material.matchMaterial(string(map.get("material"), ""));
                    if (material == null || !material.isItem() || material.isAir()) yield null;
                    int amount = Math.max(1, integer(map.get("amount"), 1));
                    yield new CrateReward(type, material, amount, BigDecimal.ZERO, "", 0, "", weight);
                }
                case CUSTOM_ITEM -> {
                    String itemId = normalize(string(map.get("item"), string(map.get("id"), ""))).replace('-', '_');
                    int amount = Math.max(1, integer(map.get("amount"), 1));
                    if (itemId.isBlank() || !plugin.customItems().exists(itemId)) yield null;
                    yield new CrateReward(type, null, amount, BigDecimal.ZERO, "", 0, itemId, weight);
                }
                case MONEY, DUCKS -> {
                    BigDecimal amount = decimal(map.get("amount"), "0");
                    if (amount.signum() <= 0) yield null;
                    yield new CrateReward(type, null, 0, amount, "", 0, "", weight);
                }
                case KEY -> {
                    String keyId = normalize(string(map.get("key"), ""));
                    int amount = Math.max(1, integer(map.get("amount"), 1));
                    if (keyId.isBlank()) yield null;
                    yield new CrateReward(type, null, 0, BigDecimal.ZERO, keyId, amount, "", weight);
                }
            };
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Ignoring invalid crate reward: " + map + " (" + exception.getMessage() + ")");
            return null;
        }
    }

    public String rewardDescription(CrateReward reward) {
        if (reward == null) return "Unknown Reward";
        return switch (reward.type()) {
            case ITEM -> reward.itemAmount() + "x " + pretty(reward.material().name());
            case CUSTOM_ITEM -> reward.itemAmount() + "x " + pretty(reward.customItemId());
            case MONEY -> plugin.economy().formatter().format(CurrencyType.MONEY, reward.currencyAmount());
            case DUCKS -> plugin.economy().formatter().format(CurrencyType.DUCKS, reward.currencyAmount());
            case KEY -> reward.keyAmount() + "x " + keys.keyDisplayName(reward.keyId());
        };
    }

    public ItemStack rewardIcon(CrateReward reward) {
        if (reward.type() == CrateReward.Type.CUSTOM_ITEM) {
            ItemStack custom = plugin.customItems().createConfigured(reward.customItemId(), 1);
            return custom == null ? new ItemStack(Material.BARRIER) : custom;
        }
        Material material = switch (reward.type()) {
            case ITEM -> reward.material();
            case MONEY -> Material.EMERALD;
            case DUCKS -> Material.FEATHER;
            case KEY -> Material.TRIPWIRE_HOOK;
            case CUSTOM_ITEM -> Material.BARRIER;
        };
        ItemStack item = new ItemStack(material);
        if (reward.type() == CrateReward.Type.ITEM) item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), reward.itemAmount())));
        return item;
    }

    private CompletableFuture<String> queueItemReward(Player player, ItemStack template, int amount, String description, String source) {
        return plugin.recoveries().queueAmount(player.getUniqueId(), template, amount, source)
                .thenApply(ignored -> {
                    if (player.isOnline()) {
                        Bukkit.getScheduler().runTask(plugin, () -> plugin.recoveries().deliverPending(player));
                    }
                    return description;
                });
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    private String pretty(String raw) {
        String[] words = raw.toLowerCase(Locale.ROOT).replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value).trim(); }
    private int integer(Object value, int fallback) {
        try { return Integer.parseInt(string(value, Integer.toString(fallback))); }
        catch (NumberFormatException exception) { return fallback; }
    }
    private BigDecimal decimal(Object value, String fallback) { return new BigDecimal(string(value, fallback)); }

    public record PreparedOpen(String crateId, String keyId, int remainingKeys, CrateReward reward) { }
    public static final class UnknownCrateException extends RuntimeException {
        public UnknownCrateException(String crateId) { super("Unknown crate: " + crateId); }
    }
    public static final class EmptyCrateException extends RuntimeException {
        public EmptyCrateException(String crateId) { super("Crate has no valid loot: " + crateId); }
    }
    public static final class CrateMaintenanceException extends RuntimeException {
        public CrateMaintenanceException() { super("Crates are temporarily in maintenance mode"); }
    }
}
