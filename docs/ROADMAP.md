# SuperDuckSystem Roadmap

## Core feature status

### ✅ Foundation
- Paper 1.21.11 / Java 21
- Gradle Kotlin DSL + GitHub Actions
- Modular YAML configuration and messages
- Async SQLite + UUID player profiles
- Persistent custom item identity through PDC
- PlaceholderAPI, Floodgate, LuckPerms and VaultUnlocked integrations
- Configurable LuckPerms group mapping, aliases, market slot perks and optional extra permission perks
- `/superduck` (`/sds`) status/reload/admin tooling

### ✅ Economy / Ducks
- Configurable primary money currency
- Ducks secondary currency
- `/bal`, `/pay`, `/eco`, `/baltop`
- BigDecimal transaction ledger
- VaultUnlocked economy provider
- PlaceholderAPI values
- Emergency economy read-only / maintenance lock

### ✅ Shop / Sell
- `/shop` configurable category GUI
- `/sell` GUI workflow
- Configurable prices and restrictions

### ✅ Auction House
- `/ah`, `/ah <search>`, optional `/ah sell <price>`
- Search, sorting, refresh and My Auctions
- Click empty My Auctions slot -> inventory picker -> price -> confirmation
- Escrow, claims and transactional purchase protection
- LuckPerms rank slot limits with configurable group names
- QDucks defaults: 3 base, 27 Plus, 47 Plus+, 47 Plus++ slots

### ✅ Buy Orders
- `/order`, `/order <search>`, `/order create`
- Searchable catalog; no held-item requirement
- Amount + price-per-item + final-cost confirmation
- Full buyer escrow, partial fills and item claims
- Click empty My Orders slot to create
- LuckPerms rank slot limits with configurable group names
- QDucks defaults: 3 base, 27 Plus, 47 Plus+, 47 Plus++ slots

### ✅ Keys / Crates
- Digital keys and configurable online-playtime milestones
- Offline time does not count; leaving resets current milestone progress
- Configurable repeating key after milestones
- QUICK and SCROLL crate opening styles
- ITEM, CUSTOM_ITEM, MONEY, DUCKS and KEY rewards
- Configurable physical spawn crate block coordinates
- Right-click crate preview with reward chances and key count
- Floating TextDisplay crate nametags
- Crate/key player stats

### ✅ Duck Tools / Custom Items
- Configurable Duck Pickaxe with QDucks default 9x9 face-oriented mining
- Configurable Duck Shovel with QDucks default 9x9 face-oriented digging
- Configurable Duck Axe full connected-tree vein felling with safety caps
- Optional diagonal tree connectivity plus log/wood/stem/hypha controls
- Normal block-break event path for protection/plugin compatibility
- Configurable custom item material/name/lore/enchantments
- `/sds giveitem`
- Duck tools supported directly in crates/rewards

### ✅ Settings / Bedrock UX
- Donut-style settings GUI
- Confirmation and instant-action toggles
- Floodgate native settings form
- Floodgate native text forms for AH/Order searches, amounts and prices
- Java virtual-sign input fallback

### ✅ Stats / Leaderboards
- `/stats [player]`
- Kills, deaths, K/D, playtime, market and economy activity, crates/keys
- `/leaderboard` for money, Ducks, kills, playtime and crates
- Stats PlaceholderAPI values

### ✅ Rewards
- `/daily` with configurable cooldown/streak rewards
- `/rewards` playtime milestone GUI
- MONEY, DUCKS, ITEM, KEY and CUSTOM_ITEM reward types
- Reward configuration validation before claims

### ✅ Operations / Recovery
- `/sds status`
- `/sds readonly`
- `/sds maintenance <module>`
- `/sds backup`
- Automatic rotating SQLite backups
- WAL + transaction/escrow design for money-sensitive systems

## v1.0 release gate
The gameplay feature set is complete. The remaining release gate is **live server validation**, not another planned gameplay module:

- Java runtime test on the real Paper 1.21.11 server
- Bedrock/Geyser runtime test
- Two-player AH concurrency test
- Two-player partial-order fill test
- LuckPerms group-name/perk mapping test against the real QDucks groups
- Protected-area 9x9 Duck Tool test
- Full-tree Duck Axe vein test across normal and large vanilla trees
- Crate disconnect/restart behavior test
- Full server restart + database recovery check
- Spark/performance check under real players
- Configure the real spawn crate coordinates

Until those tests pass, builds are tagged as a `1.0.0-RC` rather than stable `1.0.0`.
