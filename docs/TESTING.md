# SuperDuckSystem v1 Release Candidate Test Plan

This is the final live-server gate before merging `dev` to `main` and tagging stable `1.0.0`.

## 1. Startup
1. Replace the old SuperDuckSystem JAR with the newest dev JAR.
2. Fully restart Paper; do not use `/reload`.
3. Confirm SuperDuckSystem, PlaceholderAPI, LuckPerms, VaultUnlocked and Floodgate are green.
4. Run `/sds status`.
5. Run `/sds backup` and confirm a DB appears in `plugins/SuperDuckSystem/backups/`.

## 2. Economy
- `/bal`
- Admin `/eco give <player> 10000 money`
- `/pay <other-player> 100`
- Check `/baltop`
- Confirm TAB/PAPI balance placeholders update.
- Enable `/sds readonly on`; confirm normal pay/shop/AH/order money mutations are blocked; disable it after the test.

## 3. Shop / Sell
- Open `/shop`, buy an item and check the exact balance change.
- Open `/sell`, sell configured items and check the exact payout.
- Try a non-sellable/custom-data item and confirm it cannot be abused.

## 4. Auction House — two players
- Open My Auctions and click a free slot.
- Choose an inventory stack, enter a price and list it.
- Search for it with `/ah <search>` and the Search button.
- Have two players attempt to buy the same listing at nearly the same time; only one purchase must succeed.
- Cancel a listing and claim the returned item.
- Restart with an active listing and confirm it remains intact.

## 5. Buy Orders — two players
- Open My Orders and click a free slot.
- Search the item catalog, select an item, set amount and price each, then confirm the final cost.
- Confirm the whole cost is escrowed immediately.
- Fill the order partially from another player and claim the delivered items.
- Cancel the remainder and confirm only unused escrow is refunded.
- Have two sellers try filling the same remaining amount together; the order must never overfill/pay twice.

## 6. Rank slots
With LuckPerms primary groups, confirm the configured limits:
- default: 3 AH / 3 Order
- duckplus: 9 / 9
- duckplusplus: 20 / 20
- duckplusplusplus: 45 / 45

## 7. Duck Tools
Give test tools:
- `/sds giveitem <player> duck_pickaxe`
- `/sds giveitem <player> duck_shovel`
- `/sds giveitem <player> duck_axe`

Confirm:
- Pickaxe mines a 3x3 plane.
- Shovel digs a 3x3 plane.
- Axe fells connected tree logs only up to configured safety limits.
- WorldGuard/protected spawn blocks are not broken by the extra-block effect.
- Normal durability/enchantment behavior works.

## 8. Keys / Crates
- `/key` shows digital keys and current online milestone timer.
- Leave/rejoin before a milestone; current milestone progress should reset as configured.
- `/key give <player> starter 1`
- Preview/open QUICK and SCROLL crates.
- Confirm reward chances, key count and custom Duck Tool rewards render correctly.
- Right-click every configured physical spawn crate and confirm its floating nametag + preview menu.

## 9. Rewards / Stats
- `/daily`
- `/rewards`
- `/stats`
- `/leaderboard money`
- `/leaderboard kills`
- Confirm kill/death/playtime/crate stats update and survive a restart.

## 10. Bedrock / Geyser
From a Bedrock account test:
- `/settings` native form
- AH search + listing price native text form
- Order search + amount + price native text forms
- Shop/Sell/AH/Orders inventory GUIs
- Crate preview + opening
- Duck Tool behavior

## 11. Restart / recovery
With active AH listings, orders and player balances:
1. Run `/sds backup`.
2. Fully restart Paper.
3. Re-check balances, Ducks, settings, keys, stats, AH listings/orders/claims and physical crate holograms.
4. Confirm there are no SuperDuck exceptions in console.

## Stable release
After the checks above pass, merge `dev` to `main` and change the version from `1.0.0-RC1` to `1.0.0`.
