# Sprint 12 C4 — Reflex Layer Skeleton — Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-12-survival`
**Build status:** `:core:test` green (172 tests), `:fabric-mod:test` green (102 tests), `:fabric-mod:shadowJar` green

---

## Files Added

- `core/src/main/java/com/mcagent/core/reflex/ReflexSnapshot.java` — plain Java record holding `HealthStatus`, `worldTime`, `isNight`, `lightLevel`, `threats`, `inventorySummary`. All `core/` types; Minecraft-free.
- `core/src/main/java/com/mcagent/core/reflex/SurvivalReflexes.java` — Spring `@Component`. Three priority-ordered rules (FLEE > EAT > PLACE_TORCH) implemented as plain if/else per the Sprint 14 BT-deferral constraint. Counters `reflex.flee`, `reflex.eat`, `reflex.torch` wired in.
- `core/src/test/java/com/mcagent/core/reflex/SurvivalReflexesTest.java` — 14 tests: each rule's happy path, negative cases (HP fine, hunger fine, daytime/bright/no-torch), priority short-circuit (flee wins over eat, eat wins over torch), and the NONE baseline.

## Files Modified

- `core/src/main/java/com/mcagent/core/config/BotProperties.java` — added `ReflexProperties` nested static class (`enabled`, `eatThreshold`, `healthCriticalPct`, `fleeDistance`, `darkLightThreshold`) and field `private ReflexProperties reflex = new ReflexProperties()`.
- `core/src/main/resources/application.yml` — added `bot.reflex.*` section with documented defaults.
- `core/src/main/java/com/mcagent/core/CoreApplication.java` — added `SurvivalReflexes.class` to `@Import`.
- `fabric-mod/.../observer/AutonomousObserver.java` — full constructor gains `SurvivalReflexes survivalReflexes` and `BotProperties botProps` (11-arg); deprecated 3-arg delegates with nulls; `scan()` runs reflex tick before threat/opportunity reporting when `active` mode + reflex enabled + cache warm.
- `fabric-mod/.../McAgentFabricMod.java` — fetches `SurvivalReflexes` bean from Spring context, passes to `AutonomousObserver` constructor alongside `botProperties`.
- `fabric-mod/src/test/.../observer/AutonomousObserverTest.java` — 4 new tests: `activeMode_warmCache_tickReflexesIsCalled`, `passiveMode_warmCache_tickReflexesIsNeverCalled`, `activeMode_reflexDisabledInConfig_tickReflexesIsNeverCalled`, `activeMode_coldCache_tickReflexesIsNeverCalled`.

## Design Decisions

- `SurvivalReflexes` injects `BotOperations` (composite) rather than multiple role interfaces — the reflex layer legitimately needs navigation (`fleeFrom`), control (`setSafetyMode`, `eat`, `placeTorch`), and inventory (`hasItem`), so the composite is the appropriate contract.
- Reflex tick is gated on cache warmth (`!cached.isEmpty()`) — a cold-boot EMPTY snapshot must not trigger actions against stale zero-values.
- Sprint 14 will replace the if/else chain with a formal Behaviour Tree library. The current structure is intentionally flat to make that refactor straightforward.
