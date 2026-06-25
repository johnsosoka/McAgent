# Sprint 11 Bundle B — Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-11-foundation`
**Items:** F2 (Outbound Chat Correctness), F3 (Hard-Stop + Status Bypass + JobTracker skeleton)

---

## F2 — Outbound Chat Correctness

**Root cause fixed:** `drainOutbound` called `outboundQueue.poll()` before checking the send result. When `FabricChatSender` rate-limited, the message was already consumed — silently lost.

**Changes:**
- `FabricChatSender.java` — full refactor:
  - New `SendResult` enum: `SENT | RATE_LIMITED | EMPTY | DISCONNECTED`.
  - `send(String)` returns `SendResult`.
  - `lastSendTime` is now `AtomicLong` with CAS loop; thread-safe for all callers.
  - `MIN_INTERVAL_MS` changed to `1200` (volatile, was 3000 static final).
  - `setMinIntervalMs(long)` static setter for Spring wiring.
  - Pure `decide(now, prevLastSendTime, minIntervalMs, message)` helper extracted for unit testing.
- `BotEventQueue.java`:
  - `drainOutbound` now **peeks first**, sends, then polls only on `SENT/EMPTY/DISCONNECTED`; `RATE_LIMITED` leaves message at head for next tick retry.
  - Injectable `chatSender: Function<String, SendResult>` added (package-private constructor) for testability.
  - `clearInbound()` public method added (also needed by F3).
- `BotProperties.ChatProperties` — added `minIntervalMs` field (default 1200, after `summaryMaxChars`).
- `application.yml` — added `bot.chat.min-interval-ms: 1200`.
- `FabricModConfig` — `@PostConstruct configureChatSender()` calls `FabricChatSender.setMinIntervalMs(...)` from bound properties.

**Tests added:**
- `FabricChatSenderTest` — 7 tests covering all `decide()` outcomes.
- `BotEventQueueTest` — 4 tests including `outboundQueue_rateLimitedMessageRequeues`.

---

## F3 — Hard-Stop + Status Bypass + JobTracker Skeleton

**Root cause fixed:** `agent stop` still routed to LLM. Trust requires <500ms. `agent status` had no direct answer path.

**Changes:**
- `JobTracker.java` — new `@Component` in `core/telemetry/`:
  - `Job` record: `description`, `startedAt`, `playerWhoStarted`.
  - `volatile currentJob`, `volatile lastProgress`.
  - `setCurrentJob`, `updateProgress`, `clearJob`, `getCurrentJob`, `getLastProgress`, `elapsed`, `elapsedFormatted`.
- `CoreApplication.java` — `JobTracker.class` added to `@Import`.
- `MinecraftTools.java` — `jobTracker` injected; `setCurrentJob(...)` called at entry of `followPlayer`, `navigateTo`, `navigateToLocation`, `navigateToSurface`, `goToDepth`, `exploreArea`, `fleeFrom`, `navigateToNearestLocation`, `mineResource`, `buildArea`, `placeBlockAt`.
- `AutonomousObserver.java` — `getMode()` accessor added (was setter-only).
- `FabricChatHandler.java` — major update:
  - Constructor gains `BotOperations bot` and `JobTracker jobTracker`.
  - Injectable `chatSender: Consumer<String>` (package-private constructor) for unit testing.
  - `handleToggleCommand` made package-private for direct testing.
  - Hard-stop bypass: `stop/cancel/halt/abort` → `bot.cancel()` + `botEventQueue.clearInbound()` + `jobTracker.clearJob()` + send "Stopped." — no LLM.
  - Status bypass: `status / what are you doing` → `buildStatusString()` → send — no LLM.
  - `buildStatusString()` private helper: `Mode: {mode} | Observer: {on/off} | Job: {desc} ({elapsed}) | Last: {progress}`.
- `FabricModConfig.java` — constructor injects `BotProperties`; `@PostConstruct` wires sender; removed `fabricChatHandler` bean (constructed in `McAgentFabricMod`).
- `McAgentFabricMod.java` — retrieves `JobTracker` bean from context; passes `botOps` + `jobTracker` to `FabricChatHandler` constructor.

**Tests added:**
- `FabricChatHandlerTest` — 13 tests. All bypass commands verified (stop/cancel/halt/abort/status/what are you doing). Normal commands verified as not bypassed. Status string content verified.
- `JobTrackerTest` — 9 tests covering set/clear/elapsed/elapsedFormatted/progress/null handling.

---

## Test Results

```
:core:test  — all PASSED (includes JobTrackerTest, MinecraftToolsTest, etc.)
:fabric-mod:test — all PASSED (includes FabricChatHandlerTest, FabricChatSenderTest, BotEventQueueTest, AutonomousObserverTest)
```

## Files Changed

### New files
- `core/src/main/java/com/mcagent/core/telemetry/JobTracker.java`
- `core/src/test/java/com/mcagent/core/telemetry/JobTrackerTest.java`
- `fabric-mod/src/test/java/com/mcagent/fabric/FabricChatSenderTest.java`
- `fabric-mod/src/test/java/com/mcagent/fabric/FabricChatHandlerTest.java`
- `fabric-mod/src/test/java/com/mcagent/fabric/queue/BotEventQueueTest.java`

### Modified files
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatSender.java`
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java`
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricModConfig.java`
- `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java`
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java`
- `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java`
- `core/src/main/java/com/mcagent/core/CoreApplication.java`
- `core/src/main/java/com/mcagent/core/config/BotProperties.java`
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`
- `core/src/main/resources/application.yml`

## Constraints respected
- Did not touch `core/memory/chat/*`, `Assistant.java`, `SafetyValidator.java`, `FrameworkMessageBuffer.java`, `FabricBaritoneBridge.java`, `LangChain4jConfig.java`, `RollingSummaryService.java`, `LayeredChatMemory.java`.
- `minIntervalMs` appended after `summaryMaxChars` (Bundle A followup already present).
