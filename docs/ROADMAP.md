# SuperDuckSystem Roadmap

## v0.1 Foundation
- Paper 1.21.11 / Java 21
- Gradle Kotlin DSL
- Modular config and messages
- Async SQLite foundation and player profiles
- Optional PlaceholderAPI / Floodgate integration
- Custom item identity through PersistentDataContainer
- `/superduck` (`/sds`) help/status/reload
- GitHub Actions build

## v0.2 Economy
- Fully configurable primary currency
- Ducks secondary currency
- `/bal`, `/pay`, `/eco`
- Transaction ledger and formatting
- VaultUnlocked provider
- PlaceholderAPI values for TAB

## v0.3 Shop / Sell
- `/shop` configurable GUI
- `/sell` configurable sell GUI
- Configurable categories, prices and restrictions

## v0.4 Auction House
- `/ah`
- `/ah <search>`
- `/ah sell <price>` with optional confirmation
- Search input, sorting, refresh and My Items
- Player setting for instant purchases
- Item escrow and anti-dupe transaction safety

## v0.5 Orders
- `/order`
- `/order <search>`
- Buy-order escrow with amount and price per item
- Partial fills, search, sorting, refresh and My Orders

## v0.6 Keys / Crates
- Configurable playtime key milestones
- Default concept: Starter at 5 online minutes, Common at 15 online minutes
- Offline time does not count
- After milestone path completion, configurable recurring Common key (default concept: every 30 online minutes)
- Configurable crate timers and loot pools
- GUI / scrolling opening styles; no physical model animation requirement

## v0.7 Duck Tools
- Duck Pickaxe: configurable 3x3 mining area
- Duck Shovel: configurable 3x3 digging area
- Duck Axe: configurable whole-tree vein felling with safety limits

## v0.8 Settings / Stats
- Donut-style settings GUI
- Confirmations and instant-action toggles
- Notification and sound preferences
- Stats UI

## v0.9 Rewards / Leaderboards
- Configurable rewards and leaderboards

## v1.0 Production hardening
- Java and Bedrock testing
- Database backup / recovery
- Crash/restart transaction recovery
- Dupe/exploit testing
- Performance profiling
