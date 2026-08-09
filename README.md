# SuperDuckSystem

SuperDuckSystem is the configurable custom gameplay platform for QDucks SMP.

- Target: Paper 1.21.11
- Java: 21
- Build: Gradle Kotlin DSL
- Database: SQLite
- Development branch: `dev`
- Current stage: `1.0.0-RC1` feature-complete release candidate

## Included systems
- Money economy + Ducks currency + VaultUnlocked provider
- Configurable `/shop` and `/sell`
- Auction House with search, GUI listing, escrow, claims and rank slots
- Player Buy Orders with searchable item catalog, escrow and partial fills
- Digital online-playtime keys and configurable crates
- Physical spawn crate blocks with floating nametags and reward previews
- Persistent custom items
- Duck Pickaxe / Shovel 3x3 tools and Duck Axe tree-vein tool
- Java settings GUI + native Floodgate Bedrock settings/text-entry forms
- Player stats and leaderboards
- Daily and playtime rewards
- PlaceholderAPI integration
- LuckPerms rank perks
- Emergency read-only/module-maintenance controls
- Manual + automatic rotating SQLite backups

External infrastructure such as world protection, rollback, homes/RTP, permissions and crossplay stays with the dedicated server plugins already designed for those jobs.

See [`docs/ROADMAP.md`](docs/ROADMAP.md), [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), and [`docs/TESTING.md`](docs/TESTING.md).
