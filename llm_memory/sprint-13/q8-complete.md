# Q8 — Concurrency Tests Complete

**Date:** 2026-06-25  
**Branch:** feature/sprint-13-plan-layer

## Tests Added

### `fabric-mod/src/test/java/com/mcagent/fabric/queue/BotEventQueueTest.java` (8 new tests)

Added to existing file. Zero compile errors (confirmed in build output).
Awaiting FINDING-1 and FINDING-2 resolution before `:fabric-mod:test` can execute.

| Test | Q8 item | Scenario |
|------|---------|----------|
| `dedupWithinWindow_dropsDuplicate_keepsFirst` | Q8-1a | First event consumed by dispatcher; no queue-visible duplicate |
| `dedupWithinWindow_dropsDuplicate_whileFirstStillQueued` | Q8-1b | Duplicate visible in queue → dropped, counter incremented |
| `dedupAfterWindow_processesBoth` | Q8-2 | Re-enqueue after DEDUP_WINDOW_MS+100ms → both dispatched |
| `priorityOrdering_cancelBeforeNormal` | Q8-3 | CyclicBarrier holds all 3 enqueues; CANCEL dispatched before second NORMAL |
| `capacityEviction_evictsOldestNonPriority` | Q8-4 | 9th NORMAL evicts oldest; counter + outbound "busy" message |
| `capacityEviction_keepsPriorityOverNew` | Q8-5 | CANCEL-filled queue rejects incoming NORMAL instead of evicting CANCEL |
| `shutdownWhileDispatching_doesNotLeakThreads` | Q8-7 | shutdown() during active dispatch; no new dispatches after shutdown |
| *(pre-existing)* `clearInbound_removesAllPendingCommands_withoutDispatchingToLlm` | Q8-8 | Confirmed still present and passing |

### `core/src/test/java/com/mcagent/core/task/JobSupervisorConcurrencyTest.java` (4 new tests, all PASSED)

New file. All 4 tests green in `./gradlew :core:test`.

| Test | Q8 item | Result |
|------|---------|--------|
| `submit_thenCancel_terminatesCleanly` | Q8-9 | PASSED |
| `submit_thenSubmit_secondRejected` | Q8-10 | PASSED |
| `nestedPlan_notSupported_returnsClearError` | Q8-11a | PASSED |
| `craftStep_notSupported_returnsClearError` | Q8-11b | PASSED |

## Findings

Two pre-existing compile errors in the fabric-mod module block `:fabric-mod:test`. Details in:
`llm_memory/sprint-13/q8-findings.md`

These are owned by the C1/A6 agents and require no changes to Q8 test code.

## Build Status

- `:core:test` — **GREEN** (all tests pass)
- `:fabric-mod:test` — **BLOCKED** by FINDING-1 + FINDING-2 (production compile error, not Q8)
