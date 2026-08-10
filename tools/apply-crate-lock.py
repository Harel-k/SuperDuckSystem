from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILE = ROOT / "src/main/java/com/qducks/superducksystem/crate/CrateMenu.java"
text = FILE.read_text(encoding="utf-8")


def patch(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match, found {count}: {old[:140]!r}")
    text = text.replace(old, new, 1)


patch(
'''        if (keys.cachedKeys(player.getUniqueId(), crates.keyId(crateId)) <= 0) {
            messages.send(player, "crates.no-key", "<red>You do not have the required key.</red>");
            return;
        }

        crates.prepareOpen(player, crateId).whenComplete((prepared, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        if (cause instanceof KeyService.NoKeyException) {
                            messages.send(player, "crates.no-key", "<red>You do not have the required key.</red>");
                        } else if (cause instanceof CrateService.CrateMaintenanceException) {
                            player.sendRichMessage("<red>Crates are temporarily in maintenance mode.</red>");
                        } else {
                            messages.send(player, "crates.open-failed", "<red>Could not open that crate.</red>");
                        }
                        return;
                    }

                    if (crates.openingStyle(crateId).equalsIgnoreCase("SCROLL")) {
                        runScroll(player, prepared);
                    } else {
                        grantPrepared(player, prepared);
                    }
                })
        );
''',
'''        if (keys.cachedKeys(player.getUniqueId(), crates.keyId(crateId)) <= 0) {
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
'''
)

patch(
'''                if (!player.isOnline()) {
                    cancel();
                    crates.refundConsumedKey(player, prepared);
                    return;
                }
''',
'''                if (!player.isOnline()) {
                    cancel();
                    // Winner was already selected when the key was consumed. Persist/grant it
                    // rather than refunding the key and making disconnects a reroll mechanic.
                    grantPrepared(player, prepared);
                    return;
                }
'''
)

patch(
'''    private void grantPrepared(Player player, CrateService.PreparedOpen prepared) {
        crates.grant(player, prepared).whenComplete((description, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        crates.refundConsumedKey(player, prepared);
                        messages.send(player, "crates.reward-failed",
                                "<red>The reward failed, so your key was refunded.</red>");
                        return;
                    }
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
''',
'''    private void grantPrepared(Player player, CrateService.PreparedOpen prepared) {
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
'''
)

FILE.write_text(text, encoding="utf-8")
print("Crate locking and disconnect-reroll patch applied.")
