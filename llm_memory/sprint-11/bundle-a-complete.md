# Sprint 11 Bundle A — Complete

**Date:** 2026-06-25
**Items:** F1 (Tiered Chat Memory), Q5 (Atomic processUrgentObservation)
**Status:** All tests green. No files outside `core/` were touched.

---

## What Changed

### F1 — Tiered Chat Memory

Replaced `MessageWindowChatMemory` with a custom `LayeredChatMemory` that
structures context as: `[pinned system?] [rolling summary?] [working window]`.

- Eviction is FIFO. When the working window fills, the oldest message goes into
  a pending-summary buffer. When that buffer hits `summaryTrigger`, the
  `RollingSummaryService` is called and the result becomes the `<summary>` message
  at position 1 (or 0 when no pin is set).
- `setPinnedSystem(String)` is the Sprint 13 hook — currently always null.
- Config defaults: `workingWindow=12`, `summaryTrigger=8`.

### Q5 — Atomic processUrgentObservation

Rewrote `processUrgentObservation` so the framework context injection and the
`assistant.chat()` call occur inside a single `synchronized (chatMemory)` block.
Introduced `addFrameworkContextUnsafe(String)` for internal callers already holding
the lock. `processInput` was also tightened: player context injection now happens
inside the same lock as `assistant.chat()`.

---

## New Files

| Path | Purpose |
|------|---------|
| `core/src/main/java/com/mcagent/core/memory/chat/LayeredChatMemory.java` | ChatMemory implementation with pinned slot + working window + rolling summary |
| `core/src/main/java/com/mcagent/core/memory/chat/RollingSummaryService.java` | Calls ChatModel to summarize evicted message batches |
| `core/src/test/java/com/mcagent/core/memory/chat/LayeredChatMemoryTest.java` | 16 unit tests for LayeredChatMemory |
| `core/src/test/java/com/mcagent/core/service/LangChain4jServiceRealMemoryTest.java` | 3 tests: single-framework injection, wrapping, and 50×2 concurrency |

## Modified Files

| Path | Change |
|------|--------|
| `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` | Wires LayeredChatMemory + RollingSummaryService; bean return type changed to `LayeredChatMemory` / `ChatMemory` |
| `core/src/main/java/com/mcagent/core/service/LangChain4jService.java` | Atomic urgent observation; `addFrameworkContextUnsafe`; `setPinnedSystem` hook; `processInput` lock tightened |
| `core/src/main/java/com/mcagent/core/config/BotProperties.java` | Added `workingWindow=12`, `summaryTrigger=8`; deprecated `maxHistory` with Javadoc |
| `core/src/main/resources/application.yml` | Added `working-window: 12`, `summary-trigger: 8`; `max-history` kept but commented as deprecated |
| `core/src/main/java/com/mcagent/core/CoreApplication.java` | Added `RollingSummaryService` to `@Import` list |
| `core/src/test/java/com/mcagent/core/service/LangChain4jServiceTest.java` | Stripped to Mockito-only unit tests (real-memory tests moved to separate class) |

---

## Test Counts

| Class | Tests | Result |
|-------|-------|--------|
| `LayeredChatMemoryTest` | 16 | PASS |
| `LangChain4jServiceRealMemoryTest` | 3 | PASS |
| `LangChain4jServiceTest` | 2 | PASS (unchanged) |
| All other core tests | 61 | PASS (no regressions) |
| **Total** | **82** | **GREEN** |

---

## Open Questions / Follow-ups

1. **Sprint 13 pin hook:** `setPinnedSystem(String)` is wired and tested but the
   caller doesn't exist yet. TaskExecutor should call this when a goal is set/cleared.
2. **Summary latency:** The rolling summary makes a synchronous ChatModel call while
   holding the `chatMemory` lock. If the model is slow (>5 s), this will block the
   inbound dispatcher for that duration. Sprint 13 or later should consider an async
   off-thread summarization with a timeout + fallback to "drop oldest N" if the call
   hangs.
3. **`maxHistory` migration:** `BotProperties.ChatProperties.maxHistory` is `@Deprecated`
   but Spring still binds it from `application.yml`. If an operator sets only
   `max-history` (not `working-window`), the default of 12 is used silently. A future
   PR should log a WARN if `max-history` is set and `working-window` is still the default.
