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
9. Rank config IDs are independent from LuckPerms group names. Group mapping, aliases, market slot counts and optional extra permission perks are configuration, not hardcoded server assumptions.

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

Duck Pickaxe and Duck Shovel use a configurable face-oriented area with QDucks SMP defaulting to 9x9 at depth 1. Odd widths/heights from 1-9 and depth up to 3 are supported with a configurable extra-block safety cap. Duck Axe uses bounded whole-tree connected vein felling with configurable block/radius caps, diagonal connectivity, wood blocks and Nether stem/hypha support.

Every extra block is broken through the normal player block-break path so protection and logging plugins can evaluate each block independently.
