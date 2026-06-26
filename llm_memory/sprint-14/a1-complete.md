# Sprint 14 A1 — Three-Mode Model: Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-14-autonomy`

## What Was Done

- **`core/src/main/java/com/mcagent/core/observer/BotMode.java`** (new) — `STANDBY / WATCH / AUTO` enum with Javadoc defining semantics.

- **`core/src/main/java/com/mcagent/core/config/BotProperties.ObservationProperties`** — replaced `boolean enabled` + `String mode` fields with `BotMode mode = BotMode.WATCH`. Added `@Deprecated` shims: `isEnabled()`, `setEnabled(boolean)`, `getModeString()`, `setModeFromString(String)` for backward compat. Lombok `@Data` generates the canonical `getMode()` / `setMode(BotMode)`.

- **`fabric-mod/.../observer/AutonomousObserver.java`** — dropped `volatile boolean enabled` field; `onTick()` short-circuits on `BotMode.STANDBY`. Reflex layer gated on `BotMode.AUTO`. `publish()` uses enum comparison. Added `getBotMode()` / `setBotMode(BotMode)`; deprecated `getMode()` / `setMode(String)` / `isEnabled()` / `setEnabled(boolean)` as shims.

- **`fabric-mod/.../FabricChatHandler.java`** — canonical bypasses `agent standby`, `agent watch`, `agent auto` (each calls `setBotMode`, acks with `buildStatusString()`). Legacy aliases (`passive mode`, `active mode`, `start watching`, `stop watching`, `disable observation`, `observe`) still route to the new enum with DEBUG-level deprecation logs. `buildStatusString()` uses `getBotMode()` and emits the uppercase enum name.

## Tests

- `core/test/.../observer/BotModeTest` (new, 18 cases) — round-trip string↔enum conversion, `isEnabled` derivation, `setEnabled` mapping, unknown string rejection.
- `FabricChatHandlerTest` — updated mocks to `getBotMode()`; added 5 new cases for `standby/watch/auto` and legacy alias mapping.
- `AutonomousObserverTest` — updated all `config.setMode(String)` to `BotMode`; added 5 new cases for STANDBY gating, WATCH/AUTO scanning, and `getBotMode()` round-trip.

## Test Results

`./gradlew :fabric-mod:test` — **BUILD SUCCESSFUL**. All 27 AutonomousObserverTest + all FabricChatHandlerTest pass.
Core production code compiles cleanly. Pre-existing `core:test` failures (JobSupervisor*, LangChain4jServiceRealMemory*) are from Q1 concurrent work and are not regressions of A1.

## Do Not Touch (concurrent work in flight)

- `LangChain4jService.java` (Q1)
- `JobSupervisor.java` (Q1)
- `Assistant.java` (Q9)
