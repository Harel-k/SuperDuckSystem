# SuperDuckSystem v1 Release Candidate Test Plan

This is the final live-server gate before merging `dev` to `main` and tagging stable `1.0.0`.

## 1. Startup
1. Replace the old SuperDuckSystem JAR with the newest dev JAR.
2. Fully restart Paper; do not use `/reload`.
3. Confirm SuperDuckSystem, PlaceholderAPI, LuckPerms, VaultUnlocked and Floodgate are green.
4. Confirm console reports SQLite/database ready, digital keys ready, stats ready, rewards ready and the persistent item recovery inbox ready.
5. Run `/sds status` and confirm Database, Stats, Rewards, Digital keys, Floodgate, LuckPerms and VaultUnlocked provider are READY.
6. Run `/sds backup` and confirm a DB appears in `plugins/SuperDuckSystem/backups/`.
7. Restart once more and confirm the plugin starts cleanly without needing a plugin reload.

## 2. Economy / Vault
- `/bal`
- Admin `/eco give <player> 10000 money`
- `/pay <other-player> 100`
- Check `/baltop`.
- Confirm TAB/PAPI balance placeholders update.
- Confirm a Vault-using plugin sees `SuperDuckSystem` as the active economy provider and reads the same balance.
- Immediately after a restart, test one Vault balance lookup and confirm it does not throw/timeout.
- Enable `/sds readonly on`; confirm normal pay/shop/AH/order/reward money mutations are blocked; disable it after the test.

## 3. Shop / Sell
- Open `/shop`, buy an item and check the exact balance change.
- Open `/sell`, sell configured items and check the exact payout.
- Try a non-sellable/custom-data item and confirm it cannot be abused.
- Spam-click a shop purchase; every successful debit must correspond to the exact purchased quantity.
- Buy an item and disconnect immediately. Rejoin and confirm any item that could not be delivered was returned by the recovery inbox exactly once.
- Confirm a sell, then disconnect immediately. If the DB operation fails, the removed items must be returned/recovered and never disappear.
- Turn `/sds maintenance shop on` while a shop/sell GUI is already open; the stale GUI must not continue buying/selling.

## 4. Auction House — two players
- Open My Auctions and click a free slot.
- Choose an inventory stack, enter a price and list it.
- Search for it with `/ah <search>` and the Search button.
- Have two players attempt to buy the same listing at nearly the same time; only one purchase must succeed.
- Double-click a listing/purchase confirmation; the item must only sell once.
- Cancel a listing and claim the returned item.
- Disconnect immediately while claiming an AH item, then rejoin. The item must be delivered by the persistent recovery inbox exactly once.
- Disconnect immediately after submitting a listing that later fails; the removed listing item must return through inventory/recovery.
- Turn `/sds maintenance auctions on` while an AH GUI is already open; purchases/listings/cancels must be rejected.
- Restart with an active listing and confirm it remains intact.
- For a 47-slot rank, fill enough listings to confirm slots 45 and 46 are visible and manageable in My Auctions.

## 5. Buy Orders — two players
- Open My Orders and click a free slot.
- Search the item catalog, select an item, set amount and price each, then confirm the final cost.
- Confirm the whole cost is escrowed immediately.
- Fill the order partially from another player and claim the delivered items.
- Confirm items with different names/lore/enchantments/custom metadata do not satisfy an order unless they are actually similar to its template.
- Cancel the remainder and confirm only unused escrow is refunded.
- Have two sellers try filling the same remaining amount together; the order must never overfill/pay twice.
- Disconnect the seller immediately after confirming a fill that is forced to fail; removed items must return through inventory/recovery.
- Disconnect the buyer immediately while claiming delivered order items, then rejoin; the claim must be recovered exactly once.
- Turn `/sds maintenance orders on` while an Order GUI is already open; create/fill/cancel actions must be rejected.
- For a 47-slot rank, fill enough orders to confirm slots 45 and 46 are visible and manageable in My Orders.

## 6. Rank mapping and perks
With LuckPerms primary groups, confirm the configured QDucks defaults:
- default/unmatched group: 3 AH / 3 Order
- configured Plus group (default `duckplus`): 27 / 27
- configured Plus+ group (default `duckplusplus`): 47 / 47
- configured Plus++ group (default `duckplusplusplus`): 47 / 47

Also test configurability:
- Change one rank's `group-name` to a temporary LuckPerms primary group and run `/sds reload`; the player should immediately receive that rank's configured slot perks.
- Add a temporary `group-aliases` entry and confirm the alias receives the same perks after `/sds reload`.
- Add a harmless test permission under a rank's `permissions` list and confirm it is granted, then remove it and reload.
- Confirm unmatched groups always fall back to `rank-perks.default`.

## 7. Duck Tools
Give test tools:
- `/sds giveitem <player> duck_pickaxe`
- `/sds giveitem <player> duck_shovel`
- `/sds giveitem <player> duck_axe`

Confirm:
- Pickaxe mines a 9x9 face-oriented plane by default (81 total blocks including the originally broken block when all targets are valid).
- Shovel digs a 9x9 face-oriented plane by default.
- Pickaxe/Shovel respect `width`, `height`, `depth`, `max-extra-blocks`, allowed-block and blocked-block config changes after `/sds reload`.
- Axe fells the full connected tree log vein for normal and large vanilla trees up to the configured safety limits.
- Axe respects `max-blocks`, `max-radius`, `connect-diagonally`, `include-wood-blocks`, `include-stems` and `allowed-blocks`.
- With `connect-diagonally: false`, only face-connected tree blocks should chain.
- WorldGuard/protected spawn blocks are not broken by any extra-block effect.
- CoreProtect/other BlockBreakEvent listeners see the extra blocks through the normal player break path.
- Normal durability/enchantment behavior works.
- Turn `/sds maintenance custom-tools on`; extra-block/tree effects must stop immediately.

## 8. Keys / Crates
- `/key` shows digital keys and current online milestone timer.
- Leave/rejoin before a milestone; current milestone progress should reset as configured.
- `/key give <player> starter 1` (or use an actually configured key id).
- Preview/open QUICK and SCROLL crates.
- Confirm reward chances, key count and custom Duck Tool rewards render correctly.
- Right-click every configured physical spawn crate and confirm its floating nametag + preview menu.
- Give several keys and spam/double-click Open. Only one crate opening may be active for that player at a time.
- Start a SCROLL crate and disconnect while it is spinning. Rejoin: the already-selected reward must be delivered; the key must NOT be refunded for a reroll.
- Close the SCROLL inventory early. The preselected reward must still be granted exactly once.
- Disconnect during the short winner delay after the scroll stops. Rejoin and confirm item/custom-item winnings arrive through recovery.
- Turn `/sds maintenance crates on` while a physical or command crate menu is already open; opening must be rejected.

## 9. Rewards / Stats
- `/daily`
- `/rewards`
- `/stats`
- `/leaderboard money`
- `/leaderboard kills`
- Confirm kill/death/playtime/crate stats update and survive a restart.
- Claim a reward set containing money + an item, then disconnect immediately during delivery. The claim must remain consumed and the item must recover on join; money must not be claimable a second time.
- Spam `/daily`; only one claim may succeed for the same cooldown window.
- Turn `/sds maintenance rewards on`; daily/playtime reward claims must be rejected.

## 10. Bedrock / Geyser
From a Bedrock account test:
- `/settings` native form.
- AH search + listing price native text form.
- Order search + amount + price native text forms.
- Shop/Sell/AH/Orders inventory GUIs.
- Crate preview + opening.
- Duck Tool behavior, including a 9x9 Pickaxe/Shovel test.
- Repeat at least one economy, AH, Order and crate transaction from Bedrock and confirm it produces the same persistent data as Java.

## 11. Restart / recovery
With active AH listings, orders, pending claims, queued recovery items and player balances:
1. Run `/sds backup`.
2. Fully restart Paper.
3. Re-check balances, Ducks, settings, keys, stats, AH listings/orders/claims and physical crate holograms.
4. Rejoin a player with queued recovery items and confirm every pending stack is delivered once.
5. Queue more than 100 recovery stacks in a controlled test if practical; confirm the inbox drains additional batches without requiring another relog.
6. Confirm there are no SuperDuck exceptions in console.

## 12. Emergency controls
- `/sds readonly on` blocks player-triggered economy mutations but still allows admin recovery commands as designed.
- Test maintenance independently for `economy`, `shop`, `auctions`, `orders`, `crates`, `rewards` and `custom-tools`.
- Test with GUIs already open before maintenance is enabled; stale GUIs must not bypass the lock.
- `/sds readonly off` and all maintenance modules OFF before finishing the test.

## Stable release
Only after the checks above pass:
1. Review console one final time for SuperDuck errors/warnings.
2. Create one final `/sds backup`.
3. Merge `dev` to `main`.
4. Change the version from `1.0.0-RC1` to `1.0.0`.
5. Build/tag the stable release.
