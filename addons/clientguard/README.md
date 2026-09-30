# SuperDuckClientGuard

SuperDuckClientGuard is a separate Paper plugin addon for SuperDuckSystem.

Current build stage:
- Java-only client/mod scanning on join or with `/clientguard scan <player>`
- Floodgate/Bedrock skip
- CheckHacks-derived sign translation/keybind probing
- second confirmation scan before any DETECTED result reaches enforcement
- persistent Freecam strike tracking
- policy split: HACK_CLIENT / FREECAM / ALERT_ONLY
- sanction snapshots plus a full SDS database backup before destructive wipes
- SDS-owned wipe executed in one SQLite transaction
- external wipe adapter commands for systems such as homes
- auto-enforcement defaults OFF
- destructive wiping defaults OFF and fails closed if its external adapter is missing

Enforcement policy:
- confirmed HACK_CLIENT: snapshot -> SDS backup -> gameplay-progress wipe -> external wipe adapters -> 14-day tempban
- Freecam strike 1: warning/disconnect
- Freecam strike 2: final warning/disconnect
- Freecam strike 3+: 3-day tempban, no wipe
- PROTECTED/timeouts and ALERT_ONLY detections: staff alert only

The destructive route intentionally cannot be armed on QDucks while
`require-external-commands` is true and `external-commands` is empty. The
actual plugin that owns `/home` must be identified and its verified delete-all
command/API wired in before live enforcement.

The detection mechanics adapted from CheckHacks remain covered by the MIT
notice in THIRD_PARTY_NOTICES.md.
