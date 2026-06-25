# Bundle C Followup — Complete

Date: 2026-06-25

## Fixes Applied

**Fix 1 (MAJOR) — Watchdog silent death on send exception**
- `Watchdog.java`: wrapped `chatService.send(...)` in its own try/catch(Exception) to log and swallow send failures.
- Wrapped entire `check()` body in try/catch(Throwable) as belt-and-suspenders against any unexpected exception killing the scheduler task.
- `WatchdogTest.java`: added `sendThrowsRuntimeException_alertStillAttempted_andWatchdogSurvives` — configures mock to throw on `send`, verifies alert is attempted and a second alert fires after cooldown (watchdog still alive).

**Fix 2 (MINOR) — `triggerUrgentFramework` lacks MDC scope**
- `BotEventQueue.java`: generate `urgent-<id>` correlation ID and open `CorrelationId.MdcScope` around the `processUrgentObservation` call so all RunLog entries from urgent observations are attributed.

**Fix 3 (MINOR) — RunLog fallback JSON does not escape eventType**
- `RunLog.java`: replaced hand-rolled string concatenation in the catch block with `mapper.writeValueAsString(Map.of(...))` so all values are safely escaped by Jackson.
- `RunLogTest.java`: added `log_eventTypeContainingQuotes_producesValidJson` — writes event name containing `"` and `\`, verifies the JSONL line parses cleanly.

**Fix 4 (MINOR) — `writer` field threading docs**
- `RunLog.java`: added Javadoc above `private BufferedWriter writer` documenting writer-thread confinement and why `volatile` is not needed.

## Test Results

`./gradlew :core:test :fabric-mod:test` — BUILD SUCCESSFUL, all tests green.
