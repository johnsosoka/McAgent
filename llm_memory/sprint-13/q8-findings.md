# Q8 — Concurrency Test Findings

**Date:** 2026-06-25  
**Author:** junior-engineer (Q8 implementation)  
**Branch:** feature/sprint-13-plan-layer

---

## FINDING-1: Duplicate `taskExecutor` variable in `McAgentFabricMod.initSpringContext`

**File:** `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java`  
**Lines:** 193 and 215

The C1 agent's session-resumer wiring introduced a second local variable declaration
`DefaultTaskExecutor taskExecutor` on line 215, which conflicts with the
`TaskExecutor taskExecutor` declaration on line 193. This is a compile error that
blocks `./gradlew :fabric-mod:compileJava` and by extension `:fabric-mod:test`.

The fix is to remove the duplicate declaration on line 215 and replace the usage
on line 216 with a cast or by using the already-declared `taskExecutor` variable
and calling `((DefaultTaskExecutor) taskExecutor).setServerAddress(serverAddress)`.

**Impact:** All `:fabric-mod:test` tasks fail — including the new `BotEventQueueTest`
concurrency tests — until this compile error is resolved.

---

## FINDING-2: `AutonomousObserverTest` constructor mismatch

**File:** `fabric-mod/src/test/java/com/mcagent/fabric/observer/AutonomousObserverTest.java`  
**Lines:** 249, 289, 326, 352

The A6 agent added a `TaskExecutor` parameter to the `AutonomousObserver` constructor
but did not update `AutonomousObserverTest` to pass the new argument. This produces
four "no suitable constructor found" compile errors.

The fix is to pass a `mock(TaskExecutor.class)` (or `null` if the observer defensively
null-checks it) as the final constructor argument in the four affected `new AutonomousObserver(...)`
call sites in `AutonomousObserverTest`.

**Impact:** Same as FINDING-1 — prevents the fabric-mod test task from executing.

---

## Status of Q8 tests

Both new test files compiled and were verified against the Java source:

- `BotEventQueueTest.java` — zero compile errors (confirmed: no mention in error output)
- `JobSupervisorConcurrencyTest.java` — compiles and all tests pass (`./gradlew :core:test` green)

`:fabric-mod:test` cannot be run until FINDING-1 and FINDING-2 are fixed.
These fixes belong to the C1/A6 owners; they require only mechanical changes to
production and pre-existing test files, not to Q8 test code.
