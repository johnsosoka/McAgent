# Sprint 13 A3 — Heartbeat Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-13-plan-layer`
**Status:** All tests green (`./gradlew :core:test :fabric-mod:test` — BUILD SUCCESSFUL).

## What Was Built

### Modified Files

| File | Change |
|------|--------|
| `core/.../telemetry/JobTracker.java` | Added `lastPlayerInteraction`, `lastHeartbeatSent` volatile fields; `markPlayerInteraction()`, `shouldSendHeartbeat(cooldown, suppressIfRecentChat)`, `markHeartbeatSent()` methods |
| `core/.../config/BotProperties.java` | Added `JobProperties` inner class (grace 60s, cooldown 60s, suppress 30s) and `job` field |
| `core/src/main/resources/application.yml` | Added `bot.job.heartbeat-grace-seconds/cooldown-seconds/suppress-if-recent-chat-seconds` |
| `fabric-mod/.../FabricChatHandler.java` | `markPlayerInteraction()` called at every bypass path (stop/status/stats/observer toggles) and before LLM enqueue |
| `fabric-mod/.../observer/AutonomousObserver.java` | Added `InventoryOperations inventoryOps` param (13th, nullable); added `maybeEmitHeartbeat()`, `formatHeartbeat()`, `shorten()` private methods; heartbeat called in `scan()` after reflexes, before debounce/threat logic; added `Duration`/`Instant` imports |
| `fabric-mod/.../McAgentFabricMod.java` | Passes `botOps` as `inventoryOps` to `AutonomousObserver` constructor |

### New Tests

**`JobTrackerTest`** (4 new):
- `markPlayerInteraction_updatesTimestamp`
- `shouldSendHeartbeat_trueWhenBothCleared`
- `shouldSendHeartbeat_falseWhenRecentHeartbeat`
- `shouldSendHeartbeat_falseWhenRecentPlayerInteraction`

**`AutonomousObserverTest`** (2 new):
- `heartbeat_emittedWhenTaskRunningAndConditionsMet`
- `heartbeat_suppressedDuringPlayerInteraction`

## Heartbeat Format

`<step description> | <progress> | Y=<y> | <inv summary> | HP <hp>/<maxHp>`

Example: `Mine iron ore | 47/200 IRON_ORE | Y=-32 | iron_ore x47 | HP 18/20`

## Notes

- `InventoryOperations` is passed as a nullable 13th constructor param; absent in all lightweight test wiring. The 3-arg legacy constructor passes `null`.
- All existing A6 reflex-layer full-constructor calls in the test file updated to pass the new `null` for both `taskExecutor` and `inventoryOps`.
- `counters.inc("heartbeat.sent")` incremented on every emission; `runLog.log("heartbeat", ...)` emitted when `runLog` is non-null.
