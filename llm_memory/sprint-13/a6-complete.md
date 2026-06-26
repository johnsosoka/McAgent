# Sprint 13 A6 — Task-Aware Observer Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-13-plan-layer`
**Status:** All tests green.

## What Was Built

### Modified Files

| File | Change |
|------|--------|
| `core/src/main/java/com/mcagent/core/config/BotProperties.java` | Added `lowRiskThreatRadius` (default 8) to `ObservationProperties` |
| `core/src/main/resources/application.yml` | Added `bot.observation.low-risk-threat-radius: 8` |
| `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java` | Injected `TaskExecutor` as 12th constructor param; added `suppressLowRiskThreats()` method called after debounce filter |
| `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` | Resolved `TaskExecutor` bean, passes to `AutonomousObserver`; collapsed duplicate `DefaultTaskExecutor` variable |
| `fabric-mod/src/test/java/com/mcagent/fabric/observer/AutonomousObserverTest.java` | Added 4 new A6 tests; updated 4 existing reflex tests to pass `null` for `taskExecutor` |
| `fabric-mod/src/test/java/com/mcagent/fabric/queue/BotEventQueueTest.java` | Added missing checked exceptions to Q8's `priorityOrdering_cancelBeforeNormal` method signature (compile fix only, no logic change) |

## Suppression Logic

`suppressLowRiskThreats()` is called after debounce filtering, before publish. It:
- Short-circuits when `taskExecutor` is null or the threat list is empty.
- Calls `taskExecutor.currentTask()` and checks `state() == RUNNING`.
- When low-risk task is active: threats beyond `lowRiskThreatRadius` are suppressed, `observation.suppressed` counter incremented, `observation_suppressed` RunLog event emitted.
- Threats within radius, opportunities, and reflex actions are unaffected.

## Deferred

Sprint 14: HIGH risk level — when combat-tagged plans run, suppress nothing (TODO comment in `suppressLowRiskThreats`).

## Test Results

`./gradlew :core:test :fabric-mod:test` — **BUILD SUCCESSFUL**

New tests: `threatPublished_whenNoTaskActive`, `threatSuppressed_whenLowRiskTaskActive_andThreatBeyondRadius`, `threatPublished_whenLowRiskTaskActive_butThreatWithinRadius`, `opportunitiesPublishedRegardlessOfTask` — all PASSED.
