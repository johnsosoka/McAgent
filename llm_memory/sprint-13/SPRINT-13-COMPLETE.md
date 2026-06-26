# Sprint 13 — The Plan Layer — COMPLETE

**Branch:** `feature/sprint-13-plan-layer` (off Sprint 12)
**Date:** 2026-06-25
**Verdict:** 409 tests / 0 failures. Shadow JAR builds. Code review approved post-fixes. Awaiting in-game validation per `llm_memory/e2e-validation/sprint-13-validation.md`.

## What shipped

| Item | Status |
|---|---|
| C1 — Typed Plan/Step records, TaskExecutor, JobSupervisor, SessionState persistence | ✅ |
| A2 — LLM Planner role (GITM decomposer/executor split) + `decomposeAndExecute` tool | ✅ |
| A3 — Job heartbeat (1/min on long jobs, suppressed near player chat) | ✅ |
| A5 — Tool post-conditions with sync/async distinction + framework messages | ✅ |
| A6 — Task-aware observer (suppress low-risk threats during running plan) | ✅ |
| Q3 — Pre-execution approval gate (`agent go` / `agent approved`) | ✅ |
| Q8 — Concurrency tests (dedup, priority, eviction, shutdown races, supervisor lifecycle) | ✅ |
| C3 remainder (craft/smelt/chest deep MC API) | ⏸ Deferred to Sprint 14 |
| Code review fixes (2 Critical + 4 Major + 3 Minor) | ✅ |

## Test summary

```
Total tests: 409 | passed: 409 | failed: 0
(+147 from Sprint 12)
```

## Key new types

- **`core/.../task/`** — `Plan` (record + Jackson polymorphism), sealed `Step` (`NavigateStep`, `MineStep`, `WaitForStep`), sealed `WaitCondition` (`InventoryHasCondition`), `RetryPolicy`, `TerminationPolicy`, `FailureReason`, `TaskState` (now with `PENDING_APPROVAL`), `TaskSnapshot`, `TaskExecutor`/`DefaultTaskExecutor`, `JobSupervisor`, `SessionState` JPA entity, `SessionStateRepository`, `SessionResumer`, `PlanApprovalGate`, `PendingApprovalStore`, `PlanSerializer`.
- **`core/.../task/postcondition/`** — `PostCondition` sealed (`InventoryIncreased`, `InventoryDecreased`, `PositionWithin`, `NoOp`), `PostConditionChecker`, `WorldStateSnapshot`.
- **`core/.../service/`** — `Planner` (LangChain4j AI-Service), `PlanDraft`, `PlanFromDraftConverter`, `PlanValidationException`.
- New tools on `MinecraftTools`: `submitPlan`, `getPlanStatus`, `cancelPlan`, `decomposeAndExecute`.

## Behavior changes visible in-game

- **`agent mine 64 iron ore`** → LLM calls `decomposeAndExecute` → Planner emits structured Plan → submitted directly (one MineStep is below approval threshold).
- **`agent build a 7x5 oak cabin, then mine 64 iron`** → Planner emits multi-step Plan → approval gate fires → preview shown → player types `agent go` → execution begins.
- **`agent status`** during a Plan → shows step `n/N`, progress, elapsed.
- **Heartbeat** every minute on long-running jobs (suppressed for 30s after player chat).
- **Task-aware observer** suppresses threat alerts > 8 blocks while a Plan is running.
- **Disconnect mid-Plan** → `SessionResumer` restores within 5 minutes if same server.
- Tool post-conditions: synchronous tools (placeBlockAt, eat, placeTorchAtFeet) get true verification; async tools (mineResource, navigateTo, buildArea) get `"verification pending"` framework messages instead of false-negative reports.

## Known deferrals

| Item | Why | Where it goes |
|---|---|---|
| Craft / smelt / chest tools | Deep MC API; routines in Sprint 14 use existing tools | Sprint 14 routines or later |
| `JobSupervisor` async post-condition verification | A5 emits "verification pending"; final check happens via Baritone callback path | Sprint 14 polish |
| Stop-on-low-health in TerminationPolicy | Reflex layer handles health independently | Sprint 14 reflex integration |
| Plan failure escalation back to LLM | A2 stops with `FAILED`; Q1 (Sprint 14) adds explain-then-replan | Sprint 14 Q1 |
| `<multi_step_guidance>` vs `<planner_delegation_guidance>` | Rewritten to clarify boundary; consider full removal of multi_step in Sprint 14 | Sprint 14 prompt cleanup |

## Files changed (high-level)

- ~30 new files in `core/.../task/`, `core/.../task/postcondition/`, and `core/.../service/`
- ~12 modified files: `Assistant`, `MinecraftTools`, `LangChain4jConfig`, `BotProperties`, `application.yml`, `CoreApplication`, `JpaConfig`, `JobTracker`, `AutonomousObserver`, `FabricChatHandler`, `FabricModConfig`, `McAgentFabricMod`.
- ~10 new/extended test files including `JobSupervisorConcurrencyTest`, `PlanFromDraftConverterTest`, `PlanApprovalGateTest`, `PendingApprovalStoreTest`, `PlanTest`, `DefaultTaskExecutorTest`, `MinecraftToolsTest`, `FabricChatHandlerTest`, `AutonomousObserverTest`, `JobTrackerTest`.

## Validation hand-off

Run `llm_memory/e2e-validation/sprint-13-validation.md`. Critical scenarios:
- S13.1 — Plan submission via `decomposeAndExecute`.
- S13.2 — Persist across disconnect (the foundational proof).
- S13.3 — Heartbeat cadence.
- S13.4 — Approval gate + `agent go`.
- S13.6 — Cancel mid-plan.
