# Bundle B Follow-up — Code Review Fixes

**Date:** 2026-06-25
**Branch:** `feature/sprint-11-foundation`
**Status:** All four fixes applied; `./gradlew :core:test :fabric-mod:test` green.

## Fixes Applied

### Fix 1 — `cancelCurrentOperation` now clears JobTracker (MAJOR)
- `MinecraftTools.java`: added `jobTracker.clearJob()` immediately after `bot.cancel()`.
- `MinecraftToolsTest.java`: new test `cancelCurrentOperation_callsBotCancelAndClearsJob` verifies both calls.

### Fix 2 — TOCTOU in `buildStatusString` eliminated (MAJOR)
- `FabricChatHandler.java`: `getCurrentJob()` now called once; result captured in `Optional<JobTracker.Job> jobOpt`; `.map()`/`.orElse()` used throughout. Added `java.util.Optional` import.
- `FabricChatHandlerTest.java`: new `status_noJob_reportsIdleWithoutThrowing` test explicitly covers the no-job path and verifies the "idle" output.

### Fix 3 — Meaningful `clearInbound` test (MINOR)
- `BotEventQueue.java`: added package-private `inboundQueueSize()` helper (`// VisibleForTesting`).
- `BotEventQueueTest.java`: replaced hollow test with `clearInbound_removesAllPendingCommands_withoutDispatchingToLlm`, which enqueues two real commands, blocks the dispatcher inside a mock LLM, clears the queue, and asserts size == 0 and the second command was never dispatched.

### Fix 4 — `DISCONNECTED` data loss documented (MINOR)
- `BotEventQueue.java` `drainOutbound`: split `DISCONNECTED` into its own switch arm with an explanatory comment and a `log.debug` line that names the discarded message.
