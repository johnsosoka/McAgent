# Sprint 12 — Bot Stays Alive — COMPLETE

**Branch:** `feature/sprint-12-survival` (off Sprint 11)
**Date:** 2026-06-25
**Verdict:** All bundles + code review fixes merged. 262 tests, 0 failures. Shadow JAR builds. Awaiting in-game validation per `llm_memory/e2e-validation/sprint-12-validation.md`.

## What shipped

| Item | Status |
|---|---|
| C2 — Split `BotOperations` into 5 role interfaces | ✅ |
| C3 — Survival tools: `eat`, `placeTorch`, `getWorldTime`, `isNight`, `getLightLevel`, `pickupNearbyItems` | ✅ |
| C5 — Death/respawn handler + framework events | ✅ |
| Q7 — `WorldStateCache` (5-tick refresh) + cached read methods | ✅ |
| C4 — Reflex layer skeleton (3 prioritized rules) | ✅ |
| Code review fixes (1 Critical + 4 Major + 4 Minor) | ✅ |

## Test summary

```
Total tests: 262 | passed: 262 | skipped: 0 | failed: 0 | errors: 0
(+90 from Sprint 11)
```

## New packages and key types

- `core/.../service/ops/` — `NavigationOperations`, `ObservationOperations`, `InventoryOperations`, `BuildingOperations`, `BotControlOperations`. `BotOperations` becomes a composite marker.
- `core/.../reflex/` — `SurvivalReflexes` (`@Component`), `ReflexSnapshot` (record). 3 prioritized rules: HP-critical + threats → flee; HP-critical no threats → safety + framework alert; hunger low → eat; night + dark + has torch → torch. First match exits.
- `fabric-mod/.../cache/` — `WorldStateCache`, `WorldStateUpdater`. Cache refreshed every 5 ticks on the client thread; reads via `cache.hit` / `cache.miss` counters.
- `fabric-mod/.../observer/DeathDetector.java` — pure state machine for alive↔dead edge transitions.
- `fabric-mod/.../HostileMobs.java` — canonical mob list (shared by bridge + cache updater).
- `fabric-mod/.../BotControlHelpers.java` — pure-Java helpers for food slot selection, etc.

## Behavior changes visible in-game

- `agent eat bread` (or `agent eat`) — bot equips and starts eating (server-side completion ~32 ticks; optimistic-true return).
- `agent place a torch` — bot tries adjacent walls; silent on success/failure (reflex pattern).
- `agent what time is it` / `agent is it dark` — instant terse reply.
- `agent pick up items` — single `GoalComposite` pathing to all nearby `ItemEntity` positions.
- **Reflex layer** in `active` mode: bot auto-eats at hunger < 8, auto-flees at HP < 30% with threats, auto-places torches at night in dark spots if it has them. Reflex actions are silent (no chat).
- **Death detection**: framework message `"You died at (X,Y,Z). Current task suspended."`; respawn message `"Respawned at (X,Y,Z). Task suspended; awaiting player decision."`. `agent status` now shows `| Death: (X,Y,Z)` for the current session. No auto-resume.
- **Cache-backed reads**: scan/health/inventory queries no longer round-trip the client thread per call. ~5-tick refresh.

## Known deferrals

| Item | Why | Where it goes |
|---|---|---|
| Async eat confirmation (verify food rose 40 ticks later) | Optimistic-true ships Sprint 12; verification belongs to the plan layer's post-condition system | Sprint 13 A5 |
| Formal BT (Sequence/Selector/Action nodes) | Sprint 12 ships hand-coded if/else per brief | Sprint 14 C4 refactor |
| Crafting / smelting / chest deposit/withdraw tools | Not in Sprint 12 scope | Sprint 13 C3 remainder |
| Reflex actions in `watch` mode | Currently active-mode only | Sprint 14 mode collapse may revisit |
| Cache eviction on world unload | If you traverse to the Nether, the cache may be momentarily stale | Sprint 13 polish |

## Files changed

- 5 new files in `core/.../service/ops/`
- 2 new files in `core/.../reflex/`
- 2 new files in `fabric-mod/.../cache/`
- 3 new files in `fabric-mod/` (`HostileMobs.java`, `BotControlHelpers.java`, `observer/DeathDetector.java`)
- ~12 modified files (`BotOperations`, `MinecraftTools`, `Assistant`/no-touch, `BotProperties`, `application.yml`, `FabricBaritoneBridge`, `AutonomousObserver`, `FabricModConfig`, `McAgentFabricMod`, `FabricChatHandler`, `JobTracker`, `CoreApplication`)
- 8 new test files; numerous extensions to existing test classes

## Validation hand-off

Hand `llm_memory/e2e-validation/sprint-12-validation.md` to John. Critical scenarios:
- S12.2 — `agent eat bread` actually consumes (food bar rises).
- S12.3 — auto-eat reflex fires without LLM call (check JSONL log).
- S12.4 — auto-flee reflex on creeper at low HP.
- S12.6 — death detection + suspension.
- S12.9 — overnight unattended survival (the integration test).
