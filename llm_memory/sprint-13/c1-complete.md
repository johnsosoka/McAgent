# Sprint 13 C1 — Implementation Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-13-plan-layer`
**Status:** All tests green. shadowJar builds clean.

## What Was Built

### New package: `core/src/main/java/com/mcagent/core/task/`

| File | Description |
|------|-------------|
| `TaskState.java` | Enum: QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED |
| `RetryPolicy.java` | Record — maxRetries + backoff. NONE and DEFAULT constants |
| `TerminationPolicy.java` | Record — maxDuration + stopOnLowHealth. DEFAULT = 3h/true |
| `FailureReason.java` | Record + string constants: NO_BLOCKS_FOUND, BARITONE_FAILED, TIMEOUT, etc. |
| `WaitCondition.java` | Sealed interface, Jackson @JsonTypeInfo/SubTypes wired |
| `InventoryHasCondition.java` | Concrete WaitCondition: "inventory has >= N itemId" |
| `Step.java` | Sealed interface — permits NavigateStep, MineStep, WaitForStep |
| `NavigateStep.java` | Navigate to (x,y,z) with timeout + retry |
| `MineStep.java` | Mine blockType up to quantity within searchRadius |
| `WaitForStep.java` | Poll condition until satisfied or timeout |
| `Plan.java` | Top-level record with defensive step copy |
| `TaskSnapshot.java` | Immutable point-in-time view of execution state |
| `TaskExecutor.java` | Interface: submit, currentTask, snapshot, cancel |
| `DefaultTaskExecutor.java` | Spring @Component, one-at-a-time CAS guard, persists on every state change |
| `JobSupervisor.java` | Single-thread executor (mc-agent-job-supervisor), step dispatch loop |
| `SessionState.java` | JPA entity with @PrePersist-managed updatedAt |
| `SessionStateRepository.java` | JpaRepository + findFirstByStateAndUpdatedAtAfterOrderByUpdatedAtDesc |
| `PlanSerializer.java` | Jackson round-trip with inline Duration/Instant serializers (no jsr310 dep) |
| `SessionResumer.java` | On-reconnect resume within 5-minute window, server-address scoped |

### Modified Files

- `CoreApplication.java` — registered JobSupervisor, DefaultTaskExecutor, SessionResumer
- `JpaConfig.java` — added `com.mcagent.core.task` to entity/repo scan packages
- `MinecraftTools.java` — added submitPlan, getPlanStatus, cancelPlan tools; injected TaskExecutor
- `Assistant.java` — added `<plan_submission>` block after `<multi_step_guidance>`
- `McAgentFabricMod.java` — calls setServerAddress + SessionResumer.tryResume after context start

### New Tests (41 new test cases across 3 files)

- `PlanTest` — JSON round-trip for all 3 step types + WaitCondition, defensive copy, stepsFromJson
- `DefaultTaskExecutorTest` — submit/reject/cancel/state-transitions, persistence, counter increments
- `JobSupervisorTest` — NavigateStep (success/timeout), MineStep (success), WaitForStep (success), cancel mid-step, framework message emission

## Deferred Decisions

1. **Step completion via polling vs. Baritone callbacks.** Current implementation polls `getCurrentPosition()` and `countItem()` at 500 ms intervals. Callback-driven completion (subscribing to Baritone process events) would be more efficient but requires wiring through fabric-mod, breaking core isolation. Deferred to Sprint 14.

2. **MineStep "done" detection.** The supervisor polls `countItem()` for quantity gain. If Baritone finishes early (block exhausted) without reaching the target quantity, the step times out rather than failing immediately with NO_BLOCKS_FOUND. A Baritone process-event hook would catch this cleanly. Deferred.

3. **TerminationPolicy.stopOnLowHealth** is defined on the record but not yet checked in the supervisor loop. The reflex layer (Sprint 12 C4) handles health-critical fleeing independently. Wiring the policy check into the supervisor is a Sprint 14 task.

4. **Q3 approval gate** — submitPlan submits immediately. The `requestedBy` field is hard-coded to "player" until the LLM planner role (A2) wires the actual player name through.
