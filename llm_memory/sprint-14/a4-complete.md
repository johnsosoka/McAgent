# Sprint 14 A4 — Named Routines — COMPLETE

**Date:** 2026-06-25
**Branch:** `feature/sprint-14-autonomy`
**Tests:** 508 total / 0 failures (was 452 before A4; +56 new tests)
**Shadow JAR:** BUILD SUCCESSFUL

## What shipped

| Component | Status |
|---|---|
| `RoutineDefinition` JPA entity (`routine_definition` table) | ✅ |
| `RoutineRepository` (JPA, `findByName`, `existsByName`) | ✅ |
| `RoutineService` interface | ✅ |
| `DefaultRoutineService` (define / invoke / findByName / listAll / delete + Jackson tree-model substitution) | ✅ |
| `RoutineNotFoundException`, `RoutineInvocationException` | ✅ |
| `RoutineSeeder` — 5 hand-seeded routines (`returnHome`, `mineUntilFull`, `depositAndContinue`, `nightShelter`, `eatIfHungry`) | ✅ |
| `RoutineInvocation` record | ✅ |
| `AutoModeDriver` (Sprint 14 v1: night → nightShelter; else empty) | ✅ |
| `BotProperties.RoutineProperties` (`autoDriverEnabled`, `baseX/Y/Z`) | ✅ |
| `application.yml` — `bot.routine.*` section | ✅ |
| `MinecraftTools.submitRoutine` / `defineRoutine` / `listRoutines` | ✅ |
| `AutonomousObserver.maybePickIdleRoutine()` hook (AUTO mode, idle, then driver pick) | ✅ |
| `CoreApplication` — new beans registered | ✅ |
| `JpaConfig` — `com.mcagent.core.routine` added to scan/entity packages | ✅ |
| `McAgentFabricMod` — AutoModeDriver + RoutineService wired to AutonomousObserver | ✅ |

## New test files

- `DefaultRoutineServiceTest` — 11 tests (define, invoke, substitution, not-found)
- `RoutineSeederTest` — 6 tests (all 5 seeded, idempotent, systemSeeded flag)
- `AutoModeDriverTest` — 8 tests (night → nightShelter, daytime → empty, disabled)
- `MinecraftToolsTest` — 6 new tests added (submitRoutine, defineRoutine, listRoutines)

## Key design notes

- **Parameter substitution** walks the Jackson `JsonNode` tree recursively; `${param}` string nodes are replaced with typed nodes (int, double, bool, or string). Unknown placeholders are left as-is to produce a clear downstream parse error.
- **RoutineSeeder** writes directly to `RoutineRepository` (not through `RoutineService.define`) to set `systemSeeded=true`.
- **AutoModeDriver** is nullable in `AutonomousObserver` — lightweight test wiring passes null and `maybePickIdleRoutine()` no-ops safely.
- **Sprint 15+ TODO**: hunger check → `eatIfHungry`, inventory-full → `depositAndContinue`, idle fallback → `mineUntilFull` of cobblestone.

## Files changed

**New (core):** `routine/RoutineDefinition.java`, `RoutineRepository.java`, `RoutineService.java`, `DefaultRoutineService.java`, `RoutineSeeder.java`, `RoutineInvocation.java`, `AutoModeDriver.java`, `RoutineNotFoundException.java`, `RoutineInvocationException.java`

**Modified (core):** `CoreApplication.java`, `JpaConfig.java`, `BotProperties.java` (+RoutineProperties), `application.yml` (+bot.routine), `MinecraftTools.java` (+3 tools, +routineService field)

**Modified (fabric-mod):** `AutonomousObserver.java` (+autoModeDriver, routineService fields, constructor args, maybePickIdleRoutine()), `McAgentFabricMod.java` (+AutoModeDriver/RoutineService lookup and wiring)

**New (tests):** `DefaultRoutineServiceTest.java`, `RoutineSeederTest.java`, `AutoModeDriverTest.java`

**Modified (tests):** `MinecraftToolsTest.java` (+routineService mock, +6 tests), `AutonomousObserverTest.java` (+2 null args in all full-constructor calls)
