from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


reward = "src/main/java/com/qducks/superducksystem/reward/RewardService.java"

patch(
    reward,
    "import java.util.concurrent.atomic.AtomicBoolean;\n",
    "import java.util.concurrent.atomic.AtomicBoolean;\nimport java.util.function.Supplier;\n",
)

patch(
    reward,
    """    public CompletableFuture<DailyClaim> claimDaily(Player player) {
        if (!plugin.configs().rewards().getBoolean("daily.enabled", true)) return CompletableFuture.failedFuture(new IllegalStateException("Daily rewards are disabled"));
""",
    """    public CompletableFuture<DailyClaim> claimDaily(Player player) {
        if (plugin.state().readOnly() || plugin.state().maintenance("economy") || plugin.state().maintenance("rewards")) {
            return CompletableFuture.failedFuture(new IllegalStateException("Rewards are temporarily unavailable"));
        }
        if (!plugin.configs().rewards().getBoolean("daily.enabled", true)) return CompletableFuture.failedFuture(new IllegalStateException("Daily rewards are disabled"));
""",
)

patch(
    reward,
    """            return grantAll(player, definitions).handle((descriptions, error) -> {
                if (error == null) return CompletableFuture.completedFuture(new DailyClaim(reserved.streak, descriptions));
                return rollbackDaily(uuid, reserved).thenCompose(ignored -> CompletableFuture.<DailyClaim>failedFuture(unwrap(error)));
            }).thenCompose(future -> future);
""",
    """            return grantAll(player, definitions).handle((descriptions, error) -> {
                if (error == null) return CompletableFuture.completedFuture(new DailyClaim(reserved.streak, descriptions));
                // Do not roll the claim back after grant processing has started. A previous reward in
                // the sequence may already have committed; rolling back would make it claimable again.
                plugin.getLogger().severe("Daily reward grant failed after the claim was reserved for " + uuid
                        + ". The claim remains consumed to prevent duplicate rewards: " + unwrap(error).getMessage());
                return CompletableFuture.<DailyClaim>failedFuture(unwrap(error));
            }).thenCompose(future -> future);
""",
)

patch(
    reward,
    """    public CompletableFuture<List<String>> claimPlaytime(Player player, String rewardId) {
        UUID uuid = player.getUniqueId();
""",
    """    public CompletableFuture<List<String>> claimPlaytime(Player player, String rewardId) {
        if (plugin.state().readOnly() || plugin.state().maintenance("economy") || plugin.state().maintenance("rewards")) {
            return CompletableFuture.failedFuture(new IllegalStateException("Rewards are temporarily unavailable"));
        }
        UUID uuid = player.getUniqueId();
""",
)

patch(
    reward,
    """            }).thenCompose(ignored -> grantAll(player, definitions)
                    .handle((descriptions, error) -> error == null ? CompletableFuture.completedFuture(descriptions)
                            : deletePlaytimeClaim(uuid, rewardId).thenCompose(x -> CompletableFuture.<List<String>>failedFuture(unwrap(error))))
                    .thenCompose(future -> future));
""",
    """            }).thenCompose(ignored -> grantAll(player, definitions)
                    .handle((descriptions, error) -> {
                        if (error == null) return CompletableFuture.completedFuture(descriptions);
                        // As with dailies, keep the claim consumed once grant processing starts. This
                        // prevents a partially-successful reward set from being replayed for duplicates.
                        plugin.getLogger().severe("Playtime reward '" + rewardId + "' failed after being claimed by "
                                + uuid + ". The claim remains consumed: " + unwrap(error).getMessage());
                        return CompletableFuture.<List<String>>failedFuture(unwrap(error));
                    })
                    .thenCompose(future -> future));
""",
)

patch(
    reward,
    """            case "ITEM" -> {
                Material material = Material.matchMaterial(string(definition, "material", ""));
                if (material == null || material.isAir() || !material.isItem()) yield CompletableFuture.failedFuture(new IllegalArgumentException("Invalid reward material"));
                int amount = positiveInt(definition.get("amount"), 1);
                yield giveItem(player, new ItemStack(material), amount).thenApply(ignored -> amount + "x " + pretty(material.name()));
            }
            case "CUSTOM_ITEM" -> {
                String id = string(definition, "id", string(definition, "item", ""));
                int amount = positiveInt(definition.get("amount"), 1);
                ItemStack item = plugin.customItems().createConfigured(id, Math.min(amount, 64));
                if (item == null) yield CompletableFuture.failedFuture(new IllegalArgumentException("Invalid custom reward item: " + id));
                yield giveItem(player, item, amount).thenApply(ignored -> amount + "x " + id);
            }
""",
    """            case "ITEM" -> {
                Material material = Material.matchMaterial(string(definition, "material", ""));
                if (material == null || material.isAir() || !material.isItem()) yield CompletableFuture.failedFuture(new IllegalArgumentException("Invalid reward material"));
                int amount = positiveInt(definition.get("amount"), 1);
                yield queueRewardItem(player, () -> new ItemStack(material), amount,
                        amount + "x " + pretty(material.name()), "REWARD_ITEM");
            }
            case "CUSTOM_ITEM" -> {
                String id = string(definition, "id", string(definition, "item", ""));
                int amount = positiveInt(definition.get("amount"), 1);
                yield queueRewardItem(player, () -> {
                    ItemStack item = plugin.customItems().createConfigured(id, 1);
                    if (item == null) throw new IllegalArgumentException("Invalid custom reward item: " + id);
                    item.setAmount(1);
                    return item;
                }, amount, amount + "x " + id, "REWARD_CUSTOM_ITEM");
            }
""",
)

patch(
    reward,
    """    private CompletableFuture<Void> giveItem(Player player, ItemStack template, int requested) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (!player.isOnline()) throw new IllegalStateException("Player went offline");
                int left = requested;
                int max = Math.max(1, template.getMaxStackSize());
                while (left > 0) {
                    ItemStack stack = template.clone();
                    stack.setAmount(Math.min(left, max));
                    player.getInventory().addItem(stack).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                    left -= stack.getAmount();
                }
                future.complete(null);
            } catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future;
    }
""",
    """    private CompletableFuture<String> queueRewardItem(
            Player player,
            Supplier<ItemStack> itemFactory,
            int amount,
            String description,
            String source
    ) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Runnable prepare = () -> {
            try {
                ItemStack template = itemFactory.get();
                if (template == null || template.getType().isAir()) {
                    throw new IllegalArgumentException("Reward item could not be created");
                }
                plugin.recoveries().queueAmount(player.getUniqueId(), template, amount, source)
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                future.completeExceptionally(unwrap(error));
                                return;
                            }
                            if (player.isOnline()) {
                                Bukkit.getScheduler().runTask(plugin, () -> plugin.recoveries().deliverPending(player));
                            }
                            future.complete(description);
                        });
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            prepare.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, prepare);
        }
        return future;
    }
""",
)

print("Reward hardening patch applied successfully.")
