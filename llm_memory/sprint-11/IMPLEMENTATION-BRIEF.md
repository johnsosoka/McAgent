# Sprint 11 — Operations & Trust Foundation

**Branch:** `feature/sprint-11-foundation` (off `feature/issue-10-background-observation-loop`)
**Goal:** Make McAgent legible, stoppable, and free of silent message drops. Earn the right to run for hours.
**Source review:** `llm_memory/strategic-review/SYNTHESIS-and-roadmap.md` §4 Sprint 11

## Definition of done

All eleven items below merged, unit-tested where applicable, and the e2e validation guide `llm_memory/e2e-validation/sprint-11-validation.md` runs green.

---

## Items

### F1 — Tiered Chat Memory + Double-Injection Fix [CRITICAL]

**Why:** `MessageWindowChatMemory.withMaxMessages(20)` evaporates intent in ~10 turns. `processUrgentObservation` adds the same observation twice (once via `addFrameworkContext`, once as `chat()` arg).

**Files:**
- `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` (line 47–49) — replace `MessageWindowChatMemory` with new `LayeredChatMemory`.
- `core/src/main/java/com/mcagent/core/service/LangChain4jService.java` (lines 74–87) — atomic urgent observation; remove duplicate prompt text.
- New: `core/src/main/java/com/mcagent/core/memory/chat/LayeredChatMemory.java`.
- New: `core/src/main/java/com/mcagent/core/memory/chat/RollingSummaryService.java`.
- New: `core/src/main/java/com/mcagent/core/memory/chat/PinnedSystemMessage.java`.

**Implementation:**
- `LayeredChatMemory` implements `dev.langchain4j.memory.ChatMemory`. Holds:
  - One pinned `SystemMessage` (current goal / plan summary; set externally, never evicted).
  - A working ring window of N most-recent messages (default 12).
  - A rolling summary `UserMessage` injected as the second message in the window (after the system pin).
- When the window would overflow, evict the oldest message and append its content to the pending-summary buffer. When the pending-summary buffer reaches a threshold (e.g. 8 messages), call `RollingSummaryService.summarize(...)` (uses the existing `ChatModel` bean with a short summarization prompt) and merge into the rolling summary.
- `processUrgentObservation` becomes:
  ```java
  public String processUrgentObservation(String observation) {
      synchronized (chatMemory) {
          addFrameworkContextUnsafe(observation);
          return assistant.chat("React to the latest framework observation.");
      }
  }
  private void addFrameworkContextUnsafe(String message) {
      chatMemory.add(UserMessage.from("<framework>" + message + "</framework>"));
  }
  ```
- `application.yml`: change `bot.chat.max-history: 20` → `bot.chat.working-window: 12`, add `bot.chat.summary-trigger: 8`.

**Tests:**
- `LayeredChatMemoryTest` — verifies pin survives eviction, summary inserted at correct position, eviction order preserved.
- `LangChain4jServiceTest` — add concurrent-thread test: 100 `processInput` + 100 `processUrgentObservation` interleaved; assert no message duplication, no malformed prompts.

### F2 — Outbound Chat Correctness [CRITICAL]

**Why:** `BotEventQueue.drainOutbound` drops ~5/6 messages because `FabricChatSender` silently rate-limits.

**Files:**
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatSender.java` — `send()` returns `SendResult` (SENT / RATE_LIMITED / EMPTY / DISCONNECTED); use `AtomicLong` for `lastSendTime`.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` (line 207–217) — `drainOutbound` peeks first, only polls after successful send.
- Move the rate-limit constant to `BotProperties.ChatProperties.minIntervalMs` (default 1200ms — Mojang's signed-chat hard floor is 1000ms; 1200 is a safe margin).

**Implementation:**
```java
// FabricChatSender.java
public enum SendResult { SENT, RATE_LIMITED, EMPTY, DISCONNECTED }
public static SendResult send(String message) { ... }

// BotEventQueue.java drainOutbound
String message = outboundQueue.peek();
if (message == null) return;
SendResult result = FabricChatSender.send(message);
switch (result) {
    case SENT, EMPTY, DISCONNECTED -> outboundQueue.poll();   // consume
    case RATE_LIMITED -> { /* leave at head; next tick retries */ }
}
```

**Tests:**
- `FabricChatSenderTest` (extract `SendResult` logic into a pure method `decide(now, lastSendTime, minInterval, message)` → test the decision matrix).
- `BotEventQueueTest` — outbound queue under rate-limited send: assert no message dropped, correct retry behavior.

### F3 — Hard-Stop + Status Bypass [CRITICAL]

**Why:** Trust requires < 500ms hard-stop. Today `EventPriority.CANCEL` still routes to the LLM.

**Files:**
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — extend `handleToggleCommand` with `stop`/`cancel`/`halt`/`abort`/`status`/`what are you doing` bypasses.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` — new `clearInbound()` method.
- New: `core/src/main/java/com/mcagent/core/telemetry/JobTracker.java` — Spring bean that tracks the current job (set by `MinecraftTools.navigateTo/mineResource/buildArea`, cleared on completion framework event).
- `core/src/main/java/com/mcagent/core/CoreApplication.java` — register `JobTracker`.

**Implementation:**
- `JobTracker` holds: `currentJob` (description), `startedAt`, `lastProgress` (free-form String), `playerWhoStarted`.
- `FabricChatHandler` injects `BotOperations` and `JobTracker`.
- `agent stop`:
  ```java
  bot.cancel();
  botEventQueue.clearInbound();
  FabricChatSender.send("Stopped.");
  return true;  // no LLM
  ```
- `agent status`:
  ```java
  String s = buildStatus(jobTracker, autonomousObserver, bot);
  FabricChatSender.send(s);
  return true;
  ```
  Format: `Mode: AUTO | Observer: active | Job: mining IRON_ORE (47/500) 4m12s | HP: 18/20 | Inv 60%`.

**Tests:**
- `FabricChatHandlerTest` — assert bypass commands never enqueue to LLM.

### F4 — Operational Instrumentation [HIGH]

**Why:** Hours of autonomy without logs you can grep = unsupportable.

**Files:**
- New: `core/src/main/java/com/mcagent/core/telemetry/Counters.java` — Spring bean wrapping `ConcurrentHashMap<String, AtomicLong>`. Methods: `inc(String name)`, `inc(String name, long delta)`, `get(String name)`, `snapshot()`.
- New: `core/src/main/java/com/mcagent/core/telemetry/CorrelationId.java` — short ID generator; static `MDC` helpers.
- New: `core/src/main/java/com/mcagent/core/telemetry/RunLog.java` — JSONL appender writing to `${user.home}/Library/Application Support/minecraft/logs/mc-agent/run-<startTimestamp>.jsonl` (or `${MC_AGENT_RUN_LOG_DIR}` env override for tests). Async, daemon thread, blocking queue.
- `core/src/main/java/com/mcagent/core/CoreApplication.java` — register the three.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` — increment counters at every enqueue / dedup / drop / dispatch; stamp correlation ID at `InboundEvent` creation via SLF4J MDC.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java` — counters for publish/dedup/dropped/batch.
- `core/src/main/java/com/mcagent/core/service/LangChain4jService.java` — counters for llm.calls / llm.errors / llm.latency.ms (gauge as sum + count for p-stats later).
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — add `agent stats` bypass dumping `counters.snapshot()` to chat.
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — every `@Tool` method opens an MDC scope with `tool.name`, `cmd.id`.

**Tests:**
- `CountersTest`, `RunLogTest` — basic concurrency + write-on-rollover.

### F5 — 30s Watchdog [MEDIUM]

**Why:** Detect dispatcher hangs without manual log inspection.

**Files:**
- New: `core/src/main/java/com/mcagent/core/telemetry/Watchdog.java` — single-thread scheduler, polls `Counters` for `inbound.dispatched`. If no progress for 30s while queue non-empty → log `WARN` + single chat alert via `ChatService`.
- `core/src/main/java/com/mcagent/core/CoreApplication.java` — register.

**Tests:**
- `WatchdogTest` — verify alert fires after deadline, doesn't double-alert.

### Q5 — Race Fix in processUrgentObservation [CRITICAL]

Covered by F1 (atomic block in `processUrgentObservation`). Acceptance: `LangChain4jServiceTest` concurrency test from F1 passes.

### Q6 — Remove Manual Tool Catalogue [HIGH]

**Why:** Drift trap. `@Tool` annotations are auto-discovered by LangChain4j.

**Files:**
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — delete the `<available_tools>` block. Tighten `<*_guidance>` sections; move behavioral hints into them (e.g., the "use locatePlayer for X" rules go in `<entity_scanning_guidance>`).
- Add JUnit test that fails if `<available_tools>` ever reappears in the system prompt.

**Acceptance:** Each `@Tool` description on `MinecraftTools` self-documents the tool. Behavioral coordination stays as `<*_guidance>` paragraphs.

### P1 — Voice Refinement [MEDIUM]

**Why:** Personality drives trust. Today: chatty. Target: terse, structured.

**Files:**
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — add `<voice_guidance>` block (verbatim from UX review R13):
  ```
  <voice_guidance>
  Voice: terse, competent, direct. Like a field agent reporting to command.
  - Confirmations: 1–8 words. "Done." "Mining started." "Safe mode on."
  - Progress: structured. "Mining: 83/500 | Inv: 60% full"
  - Errors: specific. "Out of oak_planks (need 12, have 3). Stopped."
  - Questions: one at a time. Never more than two.
  - No filler. No apologies for normal operation. Length scales with task complexity, not response complexity.
  </voice_guidance>
  ```

### P2 — Delete `BLOCK_MAP` [LOW]

**Files:**
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` lines 63–75 — delete `BLOCK_MAP`. `resolveBlock` normalizes: lowercase, prepend `minecraft:` if absent, then `BuiltInRegistries.BLOCK.get(Identifier.parse(id))`. Return `Optional<Block>` so callers can fail clean on garbage IDs.

### P3 — Wire or Delete `SafetyValidator` [MEDIUM]

**Decision:** Wire it. Cheap. Concrete safety check we can extend.

**Files:**
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — inject `SafetyValidator`. Call `validator.validate(...)` at the head of `mineResource`, `placeBlockAt`, `buildArea`. If rejected, return the validator's message; do not invoke `bot`.
- `core/src/main/java/com/mcagent/core/service/SafetyValidator.java` — concrete checks against `BotProperties.SafetyProperties.dangerousBlocks` (TNT, LAVA defaults) and `maxMiningDepth`.

**Tests:**
- `SafetyValidatorTest` extended for `mineResource` calls at Y < `maxMiningDepth - 16`, dangerous block placement.

### P4 — Bound `seenInWindow` [LOW]

**Files:**
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java` line 9 — change `HashSet` to a bounded `LinkedHashSet`. On `add`, if `size() >= capacity` evict eldest.

---

## Parallelization plan

These can run in independent worktrees or as separate PR commits without conflict:

| Bundle | Items | Owner |
|---|---|---|
| **A — Memory & LLM service** | F1, Q5 (race fix) | senior-python-engineer |
| **B — Outbound + chat handler** | F2, F3 | senior-python-engineer |
| **C — Telemetry stack** | F4, F5 | senior-python-engineer |
| **D — Prompt / hygiene** | Q6, P1, P2, P3, P4 | junior-engineer |

Bundles A and B touch `BotEventQueue` — coordinate via shared lock-free design or sequence A→B.

---

## Acceptance checklist

- [ ] All Sprint 11 items merged
- [ ] `./gradlew :core:test :fabric-mod:test` green
- [ ] Shadow JAR builds
- [ ] e2e validation guide `sprint-11-validation.md` passes all scenarios
- [ ] Logbook entry written
- [ ] No regressions reported by John in-game
