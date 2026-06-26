# Sprint 15 Q4 — Correction Memory — COMPLETE

**Date:** 2026-06-25
**Branch:** `feature/sprint-15-memory`
**Build status:** `:core:test` modified-file tests 100% pass · `:fabric-mod:test` 8/8 passed
**Pre-existing failures:** 5 tests in `ReflectionServiceTest` / `JobSupervisorReflectionTest` (P11 counter-timing issue, unrelated to Q4)

## What was built

### `EpisodicMemoryService` (interface)
Added `writeWithImportance(type, content, sessionId, relatedTaskId, importance)` — bypasses LLM importance scoring and persists with an explicit 1-10 value (clamped).

### `DefaultEpisodicMemoryService`
- `write(...)` now short-circuits on `isEpisodicEnabled` then delegates to `writeWithImportance` after LLM scoring.
- `writeWithImportance(...)` owns the actual embed + save path, with its own `isEpisodicEnabled` guard and importance clamping.

### `MinecraftTools.rememberCorrection`
`@Tool` method that persists a correction event via `episodicMemoryService.writeWithImportance("correction", ..., 9)`. Returns `"Noted. I'll remember not to <wrongAction>."`. Uses `SessionContext` for session scoping. Increments `memory.corrections` counter and logs to `RunLog`.

### `Assistant.java`
Added `<correction_guidance>` block after `<disambiguation_guidance>`: instructs LLM to call `rememberCorrection` before stopping or revising when the player corrects it.

## Tests added
- `MinecraftToolsTest.rememberCorrection_writesEpisodicEvent_withTypeAndForcedImportance` — verifies type=correction, importance=9
- `MinecraftToolsTest.rememberCorrection_replyContainsNoted` — verifies reply acknowledgement
- `DefaultEpisodicMemoryServiceTest.writeWithImportance_skipsLLMScoring` — verifies chatModel never called
- `DefaultEpisodicMemoryServiceTest.writeWithImportance_clampsImportanceToValidRange` — clamps 15 → 10
- `DefaultEpisodicMemoryServiceTest.writeWithImportance_isNoOp_whenEpisodicDisabled`
- `AssistantSystemPromptTest.systemPrompt_containsCorrectionGuidance`

## Files modified
- `core/.../memory/episodic/EpisodicMemoryService.java`
- `core/.../memory/episodic/DefaultEpisodicMemoryService.java`
- `core/.../tools/MinecraftTools.java`
- `core/.../service/Assistant.java`
- `core/src/test/.../tools/MinecraftToolsTest.java`
- `core/src/test/.../episodic/DefaultEpisodicMemoryServiceTest.java`
- `core/src/test/.../service/AssistantSystemPromptTest.java`
- `core/src/test/.../task/DefaultTaskExecutorTest.java` (added `writeWithImportance` + `ReflectionService` stub)
- `core/src/test/.../task/JobSupervisorConcurrencyTest.java` (same fix for second anonymous impl)
- `core/src/test/.../service/LangChain4jServiceRealMemoryTest.java` (added `writeWithImportance`)
