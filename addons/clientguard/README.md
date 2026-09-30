# SuperDuckClientGuard

SuperDuckClientGuard is a separate Paper plugin addon for SuperDuckSystem.

Current build stage:
- Java-only client/mod scanning on join or with `/clientguard scan <player>`
- Floodgate/Bedrock skip
- CheckHacks-derived sign translation/keybind probing
- second confirmation scan before any DETECTED result reaches enforcement
- persistent Freecam strike tracking
- policy split: HACK_CLIENT / FREECAM / ALERT_ONLY
- auto-enforcement defaults OFF
- destructive 14-day + wipe route fails closed until the wipe coordinator is complete

Planned enforcement policy:
- confirmed HACK_CLIENT: 14-day tempban plus gameplay-progress wipe
- Freecam strike 1: warning/disconnect
- Freecam strike 2: final warning/disconnect
- Freecam strike 3+: 3-day tempban, no wipe
- PROTECTED/timeouts and ALERT_ONLY detections: staff alert only

The detection mechanics adapted from CheckHacks remain covered by the MIT
notice in THIRD_PARTY_NOTICES.md.
