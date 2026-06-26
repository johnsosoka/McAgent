# Sprint 13 A2 — LLM Planner Role Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-13-plan-layer`
**Status:** All tests green. Both `:core:test` and `:fabric-mod:test` pass.

## What Was Built

### New files: `core/src/main/java/com/mcagent/core/service/`

| File | Description |
|------|-------------|
| `Planner.java` | LangChain4j AI-Service interface — GITM Decomposer role. Stateless (no memory, no tools). Returns `PlanDraft` via `propose(String)`. System prompt enforces strict JSON-only output. |
| `PlanDraft.java` | Record holding the LLM's raw plan output: `summary` (String) + `steps` (List of Maps). |
| `PlanValidationException.java` | Unchecked exception thrown by `PlanFromDraftConverter` on structural errors. |
| `PlanFromDraftConverter.java` | Validates a `PlanDraft` map-list and builds a fully-typed `Plan`. Handles Navigate, Mine, WaitFor step types and InventoryHas conditions. Explicit rejection for unknown types, missing required fields, blank summary, empty steps. |

### Modified: `LangChain4jConfig.java`

Added a second `@Bean Planner planner(ChatModel)` — stateless, no memory, no tools, same `ChatModel` as `Assistant`. Sprint 14 P6 will split per-role.

### Modified: `MinecraftTools.java`

- Added `Planner planner` as a final constructor field (via `@RequiredArgsConstructor`).
- Added `decomposeAndExecute(String playerRequest)` `@Tool` method: calls `planner.propose(...)`, passes draft to `PlanFromDraftConverter.convert(...)`, submits the typed `Plan` to `TaskExecutor`. Returns `"Plan rejected: ..."` on `PlanValidationException`. Uses `withToolScope("decomposeAndExecute", new NoOp(), ...)`.
- Added `TaskSnapshot` import.

### Modified: `Assistant.java`

Added `<planner_delegation_guidance>` block directly after `<plan_submission>` in the system prompt, guiding the LLM to prefer `decomposeAndExecute` over chaining tool calls for multi-step tasks.

### New Tests (24 new test cases across 3 files)

- `PlannerTest` (3) — fake `ChatModel` returning hand-crafted JSON; verifies `PlanDraft` deserialization for single-step and multi-step plans.
- `PlanFromDraftConverterTest` (18) — covers all three step types, optional field defaults, all rejection paths (unknown type, missing fields, null draft, blank summary, empty steps, unknown condition type, unique ID per conversion, step ordering, `requestedBy` propagation).
- `MinecraftToolsTest` (+3) — `decomposeAndExecute_callsPlannerAndSubmitsToExecutor`, `_returnsPlanRejectedMessage_whenDraftIsInvalid`, `_returnsPlanRejectedMessage_whenStepTypeIsUnknown`. Mock `Planner` injected via constructor; `TaskExecutor` verified for `submit` call.

## Architecture Notes

- `PlanFromDraftConverter` is a stateless utility (not a Spring bean). Called directly from `MinecraftTools`.
- The `Planner` system prompt uses `timeoutSeconds` (integer) in step maps to avoid ISO-8601 serialization burden on the LLM; the converter converts to `Duration.ofSeconds(...)`.
- The `WaitFor` condition uses `"type"` as its discriminator in the Planner schema (matching the step schema), whereas the JPA/Jackson path uses `"kind"`. The converter bridges this transparently.
- Did NOT touch `AutonomousObserver.java` or `JobTracker.java` (A3 territory).

## Files Changed

- `core/src/main/java/com/mcagent/core/service/Planner.java` (new)
- `core/src/main/java/com/mcagent/core/service/PlanDraft.java` (new)
- `core/src/main/java/com/mcagent/core/service/PlanValidationException.java` (new)
- `core/src/main/java/com/mcagent/core/service/PlanFromDraftConverter.java` (new)
- `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java`
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`
- `core/src/main/java/com/mcagent/core/service/Assistant.java`
- `core/src/test/java/com/mcagent/core/service/PlannerTest.java` (new)
- `core/src/test/java/com/mcagent/core/service/PlanFromDraftConverterTest.java` (new)
- `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java`
