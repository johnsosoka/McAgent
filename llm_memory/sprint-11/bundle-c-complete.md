# Sprint 11 Bundle C Complete — F4 Instrumentation + F5 Watchdog

**Date:** 2026-06-25
**Branch:** `feature/sprint-11-foundation`
**Items:** F4 (Operational Instrumentation), F5 (30s Watchdog)

## What Landed

### New files in `core/src/main/java/com/mcagent/core/telemetry/`

- **`Counters.java`** — Spring `@Component`. Lock-free `ConcurrentHashMap<String, AtomicLong>`. Methods: `inc`, `inc(delta)`, `get`, `snapshot`, `reset`.
- **`CorrelationId.java`** — Static utility. 6-char base-36 ID generator (`ThreadLocalRandom`). `MdcScope` try-with-resources sets `cmd_id` in SLF4J MDC on enter, restores on close.
- **`RunLog.java`** — JSONL appender. Async daemon thread drains a `LinkedBlockingQueue<String>(1024)` to a per-session file. Directory: `MC_AGENT_RUN_LOG_DIR` env → macOS `~/Library/Application Support/minecraft/logs/mc-agent/` → `~/.mc-agent/logs/`. Rolls over at 50 MB. Package-private `RunLog(Counters, Path)` constructor used by tests to write into a `@TempDir`.
- **`Watchdog.java`** — Single-thread scheduler fires every 10s. Detects stall when `inbound.dispatched` has not advanced while `pending > 0` for ≥ 30s. Sends one chat alert per 60s cooldown.

### Wired counters

| Location | Counters added |
|---|---|
| `BotEventQueue.enqueueCommand` | `inbound.enqueued`, `inbound.deduped`, `inbound.dropped` |
| `BotEventQueue.processInbound` | `inbound.dispatched`, `inbound.errors`; MDC `cmd_id` via `CorrelationId.scope` |
| `BotEventQueue.enqueueOutbound` | `outbound.enqueued`, `outbound.dropped` |
| `BotEventQueue.drainOutbound` | `outbound.sent`, `outbound.rate_limited`, `outbound.discarded_disconnected` |
| `FrameworkMessageBuffer.publish` | `framework.published`, `framework.deduped`, `framework.dropped` |
| `FrameworkMessageBuffer.tryDeliver` | `framework.delivered`, `framework.batched` |
| `LangChain4jService.processInput` | `llm.calls`, `llm.errors`, `llm.latency.ms.sum`, `llm.latency.ms.count` |
| `LangChain4jService.processUrgentObservation` | `llm.urgent.*` mirror |
| `MinecraftTools` (all `@Tool` methods) | `tool.<name>.calls`, `.success`, `.errors`; MDC `tool=<name>`; `runLog.log("tool_call" / "tool_error")` |

### `InboundEvent` record

Added `String cmdId` field. Generated at enqueue time via `CorrelationId.generate()`. Propagated to MDC in `processInbound`.

### `FabricChatHandler` — `agent stats` bypass

`handleToggleCommand("stats")` reads a hand-picked subset of counters (inbound, outbound, llm, framework) and sends them as a single ≤250-char string, bypassing the LLM entirely.

### `CoreApplication` registrations

Added `Counters`, `RunLog`, `Watchdog` to `@Import`.

### Tests added / updated

- `CountersTest` — basic and concurrent (100 threads × 100 incs = 10,000 total).
- `RunLogTest` — 100 events written, all JSONL valid; MDC `cmd_id` propagated; `@TempDir` path via package-private constructor.
- `WatchdogTest` — stall detection, single-alert-per-cooldown, reset-on-progress, second-alert-after-cooldown.
- `BotEventQueueTest` — two new counter tests: outbound enqueue/sent and inbound enqueued (with blocking mock).
- `LangChain4jServiceTest` — four new tests for `llm.calls`, `llm.errors`, `llm.urgent.calls`, `llm.urgent.errors`.
- `FabricChatHandlerTest` — three new `stats` bypass tests (snapshot content, zero-value defaults, length ≤ 250).

## Open Questions / Follow-Ups

1. **`FrameworkMessageBuffer` counters share the same `Counters` instance as `BotEventQueue`** — confirmed correct (same bean passed through `McAgentFabricMod`).
2. **`RunLog` rollover** is size-triggered (50 MB). Session-duration rollover (e.g., daily) is not implemented; punted to Sprint 12+ if needed.
3. **Token/cost counters** (`llm.prompt_tokens`, `llm.completion_tokens`) are not present — LangChain4j's OpenAI response object exposes token usage but it requires additional unwrapping from the streaming response. Deferred to a future sprint.
4. **`TestRunner.java`** in `core/src/test` has a pre-existing `log` reference bug in its inner `MockBotOperations` class. It was broken before Bundle C. Excluded from scope here.
