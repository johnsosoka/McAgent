# Observability & Soft-Real-Time Correctness Review

**Author:** Orchestrator (own pass)
**Date:** 2026-06-14
**Scope:** Surface gaps that *aren't* about LLM planning or UX — they're about whether a 3-hour autonomous run is **observable, debuggable, and free of silent message loss**. These problems will not be visible from short interactive tests; they will silently degrade long-running operation.

---

## Executive Summary

McAgent is well-structured for short, reactive sessions. For multi-hour autonomy it has three classes of latent problems that other reviews are unlikely to surface:

1. **One real correctness bug** (outbound message drop loop) that will silently swallow most LLM-generated chat under sustained activity.
2. **Memory budget is unrealistic** for autonomous operation — the chat memory window is 20 messages, which evaporates after a handful of framework events.
3. **Zero operational instrumentation** — no metrics, no correlation IDs, no structured run log. We will not be able to answer "what happened in the last hour?" without re-reading raw client logs.

These are fixable cheaply and should land *before* the heavier architecture work the other reviews will recommend.

---

## Finding 1 — Outbound Chat Dropper Bug (CRITICAL)

**Files:** `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` (line 32, 79), `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatSender.java` (lines 15, 17, 33–39).

The outbound drainer in `BotEventQueue` pulls one message every `OUTBOUND_DRAIN_INTERVAL_MS = 500`. `FabricChatSender.send(...)` then enforces `MIN_INTERVAL_MS = 3000` — and on rate-limit it **drops** the message:

```java
if (elapsed < MIN_INTERVAL_MS) {
    LOGGER.debug("Chat send rate-limited ({} ms since last)", elapsed);
    return;   // <-- message is gone. Drainer already polled it.
}
```

So under steady load, ~5 of every 6 messages drained from the outbound queue are silently lost. The drainer doesn't re-enqueue, the sender doesn't return a status. There's a `log.debug` line, but at default log levels we'll never see it.

Two compounding subtleties:

- The sender's `lastSendTime` field is **non-volatile, non-atomic, non-synchronized**. It happens to work today only because the drainer is the only caller. But `FabricChatSender.send` is `public static` and `FabricChatHandler.handleToggleCommand` and `AutonomousObserver` both call it directly on other threads. Race window is small but real.
- `FabricChatSender` and `BotEventQueue` were designed independently — the system has *two* rate limiters that disagree. Bus factor: nobody owns the contract.

**Recommended fix (small):**
- Make `FabricChatSender.send` return `boolean` (sent / rate-limited) so the drainer can keep the message at the head of the queue when rejected.
- Better: collapse the two rate limiters into one. The drainer becomes the *only* gate, pacing itself to whatever Mojang's signed-chat constraint actually requires (3s is conservative).
- Make `lastSendTime` `volatile` and the check+update atomic (a single `AtomicLong.updateAndGet`), or restrict `send()` to one caller.

**Why it matters for autonomy:** during a long task, the LLM will narrate ("Mining diamond #14 of 64", "Watch out — creeper at 12 blocks east"). Today most of those will be silently swallowed. The player will think the bot has gone catatonic.

---

## Finding 2 — Chat Memory Window Is Tiny (MAJOR)

**File:** `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` (line 47–49), `core/src/main/resources/application.yml` (line 8: `max-history: 20`).

```java
@Bean
public MessageWindowChatMemory chatMemory(BotProperties botProps) {
    return MessageWindowChatMemory.withMaxMessages(botProps.getChat().getMaxHistory());
}
```

`MessageWindowChatMemory` does FIFO eviction at 20 messages. Every framework event, every `<player_context>` injection, every tool call response is a message. With the observer running at 1Hz (default `scanIntervalTicks=20`), a `<framework>` message lands per detected entity per scan. **In under a minute of active observation, the player's original instruction is gone.**

This is the foundation that 90% of multi-step planning will sit on. Until it's fixed, every architectural recommendation that says "the LLM should remember its goal" is built on sand.

**Recommended directions (rank order):**
1. Switch to `TokenWindowChatMemory` with a real budget (e.g. 32k tokens for Kimi-K2 — it supports 128k+) and a `Tokenizer` instance.
2. Separate channels: pin the active goal/plan in a system message that does **not** evict; let framework chatter rotate. LangChain4j has no first-class concept of "pinned messages" — we'll need a custom `ChatMemory` impl that holds (system + goal + working-window) and never evicts the first two.
3. Summarization: when memory hits a high-water mark, summarize the oldest N messages into a single `<summary>` tag. Voyager-style.

The prior-art review will likely surface more here; this is the floor.

---

## Finding 3 — No Operational Instrumentation (MAJOR)

Grep across `core/src/main` and `fabric-mod/src/main`:
- `Metrics|metrics|counter|gauge|histogram|Timer|measure` — **0 hits.**
- `log.error|LOGGER.error` — 9 sites.
- `log.warn|LOGGER.warn` — 22 sites.

For a 3-hour run we have no answer to:
- How many LLM calls happened? Total cost?
- How many tool calls succeeded vs. failed?
- How many framework messages were deduplicated / dropped at capacity?
- How many inbound commands were evicted because the queue was full?
- How many outbound messages were dropped by Finding 1?
- How long did the bot spend in idle / pathing / mining / dead state?
- p50/p99 latency of LLM round-trip?

This is **table stakes** for autonomy. Three concrete asks:

1. **Counters** for each queue (inbound enqueued / deduped / dropped / processed; outbound enqueued / sent / dropped / rate-limited; framework published / deduped / batched / dropped).
2. **Per-tool-call structured log** including: correlation ID, player, tool name, args, success/fail, duration, error type. Use SLF4J MDC or a JSON encoder.
3. **Per-LLM-call log**: prompt tokens, completion tokens, latency, model, cost (compute from token counts × Fireworks pricing).

Don't need Prometheus — emit to a per-session JSONL file under `~/Library/Application Support/minecraft/logs/mc-agent/` and add a chat command (`agent stats`) to dump the running totals.

---

## Finding 4 — No Correlation IDs Across Layers (MAJOR)

A single player command threads through: inbound queue → dispatcher → LLM → tool call → `BotOperations` → `ClientThreadExecutor` → Baritone listener → framework buffer → chat memory → outbound queue → sender. Each layer logs independently. There is no shared ID tying these events to the originating command.

When a user reports "the bot built the wrong thing 40 minutes into the run", we will spend hours grepping. A 6-char `cmdId` propagated through `InboundEvent` (already a record) and stamped on every downstream log line solves this. Cheap to add now, expensive to add later.

---

## Finding 5 — Logger Naming Inconsistency (MINOR)

Two patterns coexist:

```java
// FabricChatSender.java, FrameworkMessageBuffer.java
private static final Logger LOGGER = LoggerFactory.getLogger("mc_agent");
```

```java
// Everywhere else (Lombok @Slf4j)
private static final Logger log = LoggerFactory.getLogger(<Class>.class);
```

The "mc_agent" string-named logger collapses class context. Standardize on `@Slf4j` and configure a `MC-Agent` log marker / parent logger in `logback-spring.xml` (we'll need one anyway for Finding 3). Easy junior-engineer ticket.

---

## Finding 6 — `MIN_INTERVAL_MS = 3000` Is Both Wrong & Undocumented (MINOR)

CLAUDE.md (which I just authored, derived from the comment block in `BotEventQueue`) says "outbound drained at 500ms". That's literally true of the drainer, but the user-visible behavior is gated by `FabricChatSender.MIN_INTERVAL_MS = 3000`. The README's "3-second rate limiter prevents spam kicks" hints at this but doesn't connect the two.

The 3s constant is also unsourced — is it the actual Mojang chat-signing constraint? An empirical guess? If the latter, we could probably tighten it considerably and dramatically improve responsiveness, *if* Finding 1's design is fixed first.

**Ask:** find the actual signed-chat rate limit (Mojang docs / Wiki.vg). Replace the magic constant with a named, sourced value. One javadoc line. Junior-engineer task.

---

## Finding 7 — Single Inbound Dispatcher Is a Bottleneck (MINOR for now, MAJOR later)

**File:** `BotEventQueue.java` line 50, single-thread executor named `mc-agent-inbound-dispatcher`.

A single dispatcher means one slow LLM call blocks every other player command, including high-priority CANCEL. Today this works because `EventPriority.CANCEL` jumps the queue *position*, but it doesn't preempt an in-flight LLM round-trip. Fireworks streaming with 30s timeout × an in-progress tool call = ~30s of unstoppable bot.

For autonomy this is a *feature* (don't preempt mid-tool-call). For a player who shouts "STOP" because the bot is about to break their bookshelf, this is a bug.

**Direction:** allow the urgent dispatcher (already present!) to handle CANCEL by directly calling `BotOperations.cancel()` without going through the LLM at all, with an immediate chat ack. Don't wait for the inbound queue.

---

## Finding 8 — No Heartbeat / Liveness (MINOR for short sessions, CRITICAL for autonomy)

There's no concept of "is the bot alive and making progress?" If `inboundDispatcher` deadlocks, Baritone gets stuck in an unwinnable goal, the LLM hangs on a 30s timeout 10 times in a row, or the urgent dispatcher silently OOMs — the player has no signal until they manually check.

**Recommended:** a watchdog. Once per tick (or every N seconds), record `lastActivityAt = now()` from any dispatcher heartbeat. If `now - lastActivityAt > 30s` *and* there are pending events, flag "bot stalled" — log it, send one chat message, optionally cancel.

This is the kind of thing that turns "the bot did something weird" reports from forensic into actionable.

---

## Cross-Cutting Recommendation

The above forms a coherent **"Operations" workstream** — a single sprint that delivers:

- `Counters` bean (atomic longs, label sets) wired into both queues, the framework buffer, the LLM service.
- A `RunLog` SLF4J appender writing JSONL to `~/.../logs/mc-agent/run-<startTimestamp>.jsonl`.
- A correlation-ID stamp at `InboundEvent` creation, propagated via SLF4J MDC.
- The outbound rate-limit bug fix.
- `MessageWindowChatMemory` → `TokenWindowChatMemory` with a pinned-system custom impl.
- A `agent stats` chat command that prints the counter table.
- A 30s watchdog.

Estimated 1 focused sprint. Pays for itself the first time a multi-hour run goes sideways.

---

## Open Questions

- Do we want any of this remote (telemetry to a homelab Grafana) or strictly local-JSONL?
- Should the LLM be allowed to read its own counters / logs as a tool? ("agent why are you slow?") This is fun but a footgun.
- What's the right per-session cost cap for Fireworks? Hard fail at $1? $10? Player-configurable?
