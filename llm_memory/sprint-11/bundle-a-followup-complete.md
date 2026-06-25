# Sprint 11 Bundle A — Follow-up Fixes Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-11-foundation`
**Status:** All tests green (`./gradlew :core:test` — 89 tests PASS)

---

## Fixes Applied

### Fix 1 — `max-history` silently ignored (CRITICAL)
- Added `DEFAULT_WORKING_WINDOW=12` and `DEFAULT_MAX_HISTORY=20` constants to `ChatProperties`.
- Added `getEffectiveWorkingWindow()` with three-branch logic: prefer `workingWindow` if changed; honour `maxHistory` with a SLF4J WARN if changed; else return default.
- `LangChain4jConfig.chatMemory(...)` now calls `chat.getEffectiveWorkingWindow()`.
- `RollingSummaryService` moved from `@Service` to `@Bean` in `LangChain4jConfig` (required to pass `summaryMaxChars`); removed from `CoreApplication @Import`.
- New test class: `LangChain4jConfigTest` (3 tests) — uses Logback `ListAppender` to assert the WARN fires exactly once on the deprecated-only branch. Added `logback-classic:1.5.12` as `testImplementation`.

### Fix 2 — `priorSummary` unbounded growth (MAJOR)
- Added `summaryMaxChars=800` field to `BotProperties.ChatProperties` (placed before `responseTimeoutSeconds`, ahead of Bundle B's `minIntervalMs`).
- Added `summary-max-chars: 800` to `application.yml`.
- `RollingSummaryService` now takes `maxPriorSummaryChars` constructor arg; `truncateIfNeeded()` trims to last N chars with leading `"…"` and logs DEBUG.
- New test class: `RollingSummaryServiceTest` (5 tests) covering truncation, pass-through, null, exact-limit, and prompt-segment assertion.

### Fix 3 — `flushPendingToSummary` implicit precondition (MINOR)
- Added `assert Thread.holdsLock(this)` as first line of `flushPendingToSummary()`.
- Added Javadoc noting caller must hold the monitor.

## Test count delta
| Class | Tests |
|-------|-------|
| `LangChain4jConfigTest` (new) | 3 |
| `RollingSummaryServiceTest` (new) | 5 |
| Existing core tests | 81 |
| **Total** | **89** |
