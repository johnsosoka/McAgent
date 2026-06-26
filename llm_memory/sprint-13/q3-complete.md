# Sprint 13 Q3 — Pre-Execution Approval Gate Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-13-plan-layer`
**Status:** All tests green. `:core:test` (277) and `:fabric-mod:test` pass.

## What Was Built

### New files

| File | Description |
|------|-------------|
| `core/.../task/PendingApprovalStore.java` | Thread-safe `AtomicReference<Plan>` holder. set / peek / getAndClear / clear. Spring `@Component`. |
| `core/.../task/PlanApprovalGate.java` | Evaluates step-count (> threshold) and cumulative-duration (> threshold seconds) gates. Spring `@Component`. Build-volume gate deferred to Sprint 14. |

### Modified files

| File | Change |
|------|--------|
| `TaskState.java` | Added `PENDING_APPROVAL` enum value between `QUEUED` and `RUNNING`. |
| `BotProperties.java` | Added `PlanProperties` nested class with `approvalStepThreshold=3` and `approvalDurationSeconds=300`. Added `plan` field. |
| `CoreApplication.java` | Registered `PendingApprovalStore` and `PlanApprovalGate` in `@Import`. |
| `MinecraftTools.java` | Injected `PlanApprovalGate` and `PendingApprovalStore`. Modified `decomposeAndExecute` to check gate before submitting; on approval required: stores plan, sends preview. Added package-private `formatPlanPreview(Plan)` helper (max 240 chars). |
| `FabricChatHandler.java` | Added `RunLog`, `TaskExecutor`, `PendingApprovalStore` fields and constructor params. Hard-stop now calls `pendingApprovalStore.clear()`. Added `go` / `approved` / `go <suffix>` bypass: retrieves + submits pending plan, increments `plan.approved` counter, logs to `RunLog`. |
| `McAgentFabricMod.java` | Retrieves `PendingApprovalStore` from Spring context and injects into `FabricChatHandler`. |
| `application.yml` | Added `bot.plan.approval-step-threshold: 3` and `bot.plan.approval-duration-seconds: 300`. |

### New tests

- `PlanApprovalGateTest` (8 cases) — single/exact-threshold/over-threshold step count; single/cumulative duration; custom thresholds via properties.
- `PendingApprovalStoreTest` (9 cases) — empty initial state; set+peek; non-destructive peek; set replaces; getAndClear consumes once; clear; no-op clear on empty.
- `MinecraftToolsTest` (+3 cases) — `decomposeAndExecute_pendingApprovalForComplexPlan_returnsPreviewNotSubmission`; `formatPlanPreview_includesSummaryStepsAndEstimate`; `formatPlanPreview_truncatesLongPlanToMaxLength`.
- `FabricChatHandlerTest` (+6 cases) — `stop_clearsPendingApprovalPlan`; `agent_go_submitsPendingPlan_sendsAck_clearsStore`; `agent_approved_submitsPendingPlan_asAlias`; `agent_go_withNoPending_repliesEmpty`; `agent_go_withPrefix_isAlsoHandled`; `agent_go_submitFailure_sendsFriendlyErrorMessage`.

## Design Decisions

- `formatPlanPreview` is package-private (not private) so `MinecraftToolsTest` can call it directly for unit testing without a full plan execution path.
- `PendingApprovalStore` holds at most one pending plan; a second `set()` silently replaces the first. This matches the one-plan-at-a-time executor semantics.
- Hard-stop clears `PendingApprovalStore` so stale plans don't survive an `agent stop`.
- Build-volume gate (> 50 blocks) is documented as deferred to Sprint 14 in `PlanApprovalGate` Javadoc.
