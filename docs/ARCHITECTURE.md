# Architecture

SuperDuckSystem is the custom gameplay layer for QDucks SMP. Mature infrastructure stays external: LuckPerms, TAB, WorldGuard, WorldEdit, CoreProtect, HuskHomes, BetterRTP, Multiverse, Geyser/Floodgate and ViaVersion.

## Design rules

1. Gameplay values and display text should be configurable unless a technical constant cannot reasonably be configurable.
2. Player identity is UUID-based.
3. Database/file I/O must not block the Paper main thread.
4. Custom items are identified using PersistentDataContainer IDs, never only display names or lore.
5. Economy/market mutations will go through transaction services rather than direct balance edits.
6. Optional integrations must fail gracefully when the external plugin is absent.
7. Java players use inventory GUIs; Bedrock players can receive Floodgate native forms when a native form provides a better experience.
8. Market systems must be designed for restart safety, duplicate-click safety and full-inventory/disconnect cases.

## Planned modules

- Economy
- Ducks currency
- Shop / Sell
- Auction House
- Orders
- Keys / Crates
- Settings
- Stats / Leaderboards
- Custom items / Duck tools

## Duck tools

Duck Pickaxe and Duck Shovel use a configurable 3x3 face-oriented area (9 blocks at depth 1 by default). Duck Axe uses bounded whole-tree vein felling.
