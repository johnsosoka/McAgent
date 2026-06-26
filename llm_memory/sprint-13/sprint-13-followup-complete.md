# Sprint 13 — Code Review Followup Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-13-plan-layer`
**Status:** All fixes applied. `:core:test` and `:fabric-mod:test` green. `shadowJar` builds.

## Fixes Applied

**Fix 1 — PlanApprovalGate NPE on null timeout**
`requiresApproval` now guards `s.timeout()` with null-check, contributing 0s for null timeouts.
`formatPlanPreview` received the same guard (consistent path). Two new tests added to `PlanApprovalGateTest`.

**Fix 2 — WaitCondition discriminator standardized on `"type"`**
`WaitCondition.java`: `property = "kind"` → `property = "type"`. `submitPlan` `@P` docstring updated to match.
`PlanFromDraftConverter` and `Planner` were already using `"type"` — no change needed. Two new round-trip tests in `PlanTest` verify JSON contains `"type":"InventoryHas"` and the `stepsFromJson` path parses `WaitForStep` with `"type"` discriminator correctly.

**Fix 3 — Async post-condition false negatives**
Introduced `ToolPC` record (pc + sync flag) and `ASYNC_TOOLS` set. `withToolScope` now emits `"(async, verification pending)"` for Baritone-backed tools (`mineResource`, `navigateTo`, `buildArea`) instead of capturing a stale "after" snapshot. `buildArea` volume calc fixed from hard-coded 1 to actual `(x2-x1+1)*(y2-y1+1)*(z2-z1+1)`. Existing pre-existing framework-message tests updated to reflect async behavior. New test `withToolScope_asyncTool_doesNotReportFalseFailure` added.

**Fix 4 — Planner LLM retry**
`decomposeAndExecute` wraps `planner.propose(...)` in try/catch with one retry. Returns user-friendly message on double failure. New test `decomposeAndExecute_plannerFailsTwice_returnsUserFriendlyMessage` added.

**Fix 5 — SessionResumer serverAddress race**
`DefaultTaskExecutor.serverAddress` initializes to `null` (was `"unknown"`). `submit()` throws `IllegalStateException` if address is null. Tests in `DefaultTaskExecutorTest` and `JobSupervisorConcurrencyTest` updated to call `setServerAddress` before submit. New test `submit_throwsIllegalState_whenServerAddressNotSet` added.

**Fix 6 — JobSupervisor counter double-count + missing catch + volatile AtomicBoolean**
Removed `volatile` from `cancelRequested` (initialized at field declaration). Removed duplicate `counters.inc("plan.cancelled")` from both cancel-check branches in `runPlan`. Wrapped entire `runPlan` body in `try/catch(Throwable)` with best-effort `onUpdate(FAILED)` callback on crash.

**Fix 7 — `<multi_step_guidance>` vs `<planner_delegation_guidance>` tension**
`Assistant.java` `<multi_step_guidance>` block replaced with a clarifying note directing single-tool sequences vs. multi-step tasks to `decomposeAndExecute`, with a pointer to `<planner_delegation_guidance>`.

**Fix 8 — PendingApprovalStore silent replacement**
`decomposeAndExecute` checks `pendingApprovalStore.peek().isPresent()` before calling `set`. Prepends `"Replacing previous pending plan. "` when a plan exists. New test `decomposeAndExecute_replacesExistingPendingPlan_prependsNotice` added.

**Fix 9 — persistSnapshot swallowed exception test**
New test `persistSnapshot_swallowsExceptionsAndContinues` — `repository.save()` throws; verifies `submit()` does not propagate, `current.get()` is non-null, supervisor received the job, counter incremented.
