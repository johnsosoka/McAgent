# Sprint 14 Q1 — Explain-then-Replan on Failure — Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-14-autonomy`
**Status:** All tests green (`:core:test`, `:fabric-mod:test`). No files from A1/Q9 territory touched.

## What Was Built

### New production files

| File | Description |
|------|-------------|
| `core/.../service/FailureDecision.java` | Record returned by the explainer: `explanation`, `action` (RETRY/REVISE/ABANDON), `revisedPlan`. Helper predicates `isRetry()`, `isRevise()`, `isAbandon()`. |
| `core/.../task/TaskFailure.java` | Context object built by `JobSupervisor` at failure time. Carries `taskId`, `planSummary`, `stepIndex`, `totalSteps`, `stepDescription`, `reason`, `retriesUsed`, `worldStateSnapshot`. `toStructuredPrompt()` formats it for the LLM. |
| `core/.../service/PlanFailureExplainer.java` | LangChain4j AI-Service (stateless, no memory, no tools). `@SystemMessage` encodes DEPS Explain rules: RETRY for transient, REVISE with new plan JSON for structural, ABANDON when infeasible. Never RETRY if retriesUsed >= 2. |

### Modified production files

| File | Change |
|------|--------|
| `LangChain4jConfig.java` | Added `@Bean PlanFailureExplainer planFailureExplainer(ChatModel)` — same model as Assistant for Sprint 14 v1 (P6 will split). |
| `LangChain4jService.java` | Injected `PlanFailureExplainer failureExplainer` (constructor field). Added `processFailureEscalation(TaskFailure)`: calls explainer, increments `llm.failure_explain.*` counters, logs to RunLog, returns safe ABANDON on exception. |
| `BotProperties.java` | Added `PlanProperties.maxEscalationsPerPlan` (default 2) — the escalation cap per plan run. |
| `JobSupervisor.java` | Added `BotProperties botProperties` constructor field. Rewrote `runPlan` failure path: calls `handleStepFailure` → `langChainService.processFailureEscalation`. RETRY decrements loop index (retry same step). REVISE replaces remaining steps via `PlanFromDraftConverter`. ABANDON → `markFailed` + `notifyPlayerAndFramework`. Cap forces ABANDON after `maxEscalationsPerPlan` calls without further LLM invocations. Added `EscalationOutcome` inner record. |

### New test files

| File | Test cases |
|------|-----------|
| `PlanFailureExplainerTest.java` | 4 tests: RETRY decision, ABANDON decision, REVISE decision with revisedPlan, revisedPlan steps parsed correctly. |
| Additions to `LangChain4jServiceTest.java` | 5 tests: calls explainer, increments calls counter, increments latency counter, returns ABANDON on exception, increments errors counter on exception. |
| Additions to `JobSupervisorConcurrencyTest.java` | 4 tests: ABANDON → FAILED transition, RETRY → step succeeds on re-attempt, REVISE → executes revised plan to completion, escalation cap forces ABANDON without extra LLM call. |

### Existing test fixes

- `JobSupervisorTest.setUp` — added default stub for `processFailureEscalation` → ABANDON so existing failure-expects-FAILED tests continue to work.
- `JobSupervisorTest`, `JobSupervisorConcurrencyTest`, `DefaultTaskExecutorTest.FakeJobSupervisor` — updated `JobSupervisor` constructor call to pass `new BotProperties()` as the 6th arg.
- `LangChain4jServiceRealMemoryTest` — added `mock(PlanFailureExplainer.class)` to the 3 direct `LangChain4jService` constructor calls.

## Constraints Respected

- Did NOT touch `Assistant.java`, `AutonomousObserver.java`, `FabricChatHandler.java` (A1 territory).
- Did NOT touch `AssistantSystemPromptTest.java` (Q9 territory).
- `BotProperties.java` touched only to add `PlanProperties.maxEscalationsPerPlan` — no A1 fields disturbed.
- Both `:core:test` and `:fabric-mod:test` green.
