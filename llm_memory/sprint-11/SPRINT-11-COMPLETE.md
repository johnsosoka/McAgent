# Sprint 11 — Operations & Trust Foundation — COMPLETE

**Branch:** `feature/sprint-11-foundation` (off `feature/issue-10-background-observation-loop`)
**Date:** 2026-06-25
**Verdict:** All bundles merged. 172 tests, 0 failures. Shadow JAR builds. Awaiting in-game validation per `llm_memory/e2e-validation/sprint-11-validation.md`.

## What shipped

| Item | Bundle | Status |
|---|---|---|
| F1 — Tiered chat memory (LayeredChatMemory + RollingSummaryService) | A | ✅ |
| Q5 — Atomic processUrgentObservation (race + double-injection fix) | A | ✅ |
| F2 — Outbound chat correctness (SendResult enum + retry on rate-limit) | B | ✅ |
| F3 — Hard-stop + status bypass + JobTracker skeleton | B | ✅ |
| F4 — Counters + CorrelationId + RunLog (JSONL) | C | ✅ |
| F5 — 30s Watchdog (stall detection + recovery) | C | ✅ |
| Q6 — Removed manual tool catalogue from system prompt | D | ✅ |
| P1 — Voice refinement (`<voice_guidance>`) | D | ✅ |
| P2 — Deleted BLOCK_MAP — registry-based block resolution | D | ✅ |
| P3 — Wired SafetyValidator (BUILD + MINE) | D | ✅ |
| P4 — Bounded seenInWindow in FrameworkMessageBuffer | D | ✅ |

## Test summary

```
Total tests: 172 | passed: 172 | skipped: 0 | failed: 0 | errors: 0
```

By module:
- `core`: ~95 tests
- `fabric-mod`: ~77 tests

Both shadow JAR (`mc-agent-fabric-0.2.0-SNAPSHOT-all.jar`, 59 MB) and dev JAR build clean.

## New packages

- `core/.../memory/chat/` — `LayeredChatMemory`, `RollingSummaryService`
- `core/.../telemetry/` — `Counters`, `CorrelationId`, `RunLog`, `Watchdog`, `JobTracker` (added by Bundle B)

## Known deferrals

| Item | Why | Where it goes |
|---|---|---|
| In-flight LLM call continues after `agent stop` | Latent pre-Sprint-11 gap (Bundle B review flagged). Cancel only clears queue + Baritone; the active HTTP call continues for ~30s. | Sprint 14 (or earlier if it bites). Tracked in `bundle-b-followup-complete.md`. |
| Synchronous summarization holds chatMemory lock | Bundle A open-Q. At default trigger (8 messages) infrequent; under steady fast chat could stall dispatcher. | Sprint 13 (async option) |
| Tool-counter keys mix `dot.separated` with `camelCase` tool names | Bundle C review minor. Not functional. | Polish task. |
| UX R5 (per-block chat in `placeBlockAt`) | Not in Sprint 11 scope; deferred to Sprint 12 building work. | Sprint 12 |
| LLM token/cost counters | Bundle C open-Q. Requires unwrapping the LangChain4j response stream. | Sprint 14 P9 (session cost cap) |

## What you can do now (in-game)

Per `llm_memory/e2e-validation/sprint-11-validation.md`:

- `agent stop` / `agent cancel` / `agent halt` / `agent abort` — instant cancel, no LLM round-trip.
- `agent status` / `agent what are you doing` — instant structured status, no LLM round-trip.
- `agent stats` — dump of operational counters to chat.
- All previous Sprint 3–10 features still work. Tool catalogue moved to LangChain4j auto-discovery; nothing should regress.

## Log files

A run log is written to:
- `~/Library/Application Support/minecraft/logs/mc-agent/run-<timestamp>.jsonl` (macOS)
- `~/.mc-agent/logs/run-<timestamp>.jsonl` (Linux/Windows fallback)
- Override via `MC_AGENT_RUN_LOG_DIR` env var.

Each event is a single JSON line: `{"ts":"<ISO>","cmd_id":"<base36>","event":"<type>",...}`.

Useful greps:
- `cmd_id=<id>` — reconstruct one command's lifecycle.
- `event=tool_call` — every tool invocation.
- `event=llm_call` / `event=llm_urgent_call` — every LLM round-trip.
- `event=outbound_dropped` — anything silently lost (should be empty in normal use).
- `event=watchdog_stall` — dispatcher stalls.

## Files changed (high-level)

**Core (new):**
- `core/.../memory/chat/LayeredChatMemory.java`
- `core/.../memory/chat/RollingSummaryService.java`
- `core/.../telemetry/Counters.java`
- `core/.../telemetry/CorrelationId.java`
- `core/.../telemetry/RunLog.java`
- `core/.../telemetry/Watchdog.java`
- `core/.../telemetry/JobTracker.java`

**Core (modified):**
- `Assistant.java`, `LangChain4jService.java`, `LangChain4jConfig.java`, `CoreApplication.java`, `BotProperties.java`, `MinecraftTools.java`, `SafetyValidator.java`, `application.yml`

**Fabric-mod (modified):**
- `FabricChatSender.java`, `FabricChatHandler.java`, `BotEventQueue.java`, `FrameworkMessageBuffer.java`, `FabricBaritoneBridge.java`, `FabricModConfig.java`, `McAgentFabricMod.java`, `InboundEvent.java`

**Tests added/extended:**
- `LayeredChatMemoryTest`, `RollingSummaryServiceTest`, `CountersTest`, `RunLogTest`, `WatchdogTest`, `JobTrackerTest`, `FabricChatSenderTest`, `FabricChatHandlerTest`, `BotEventQueueTest`, `LangChain4jConfigTest`, `LangChain4jServiceRealMemoryTest`, `AssistantSystemPromptTest`, `FrameworkMessageBufferTest`, plus extensions to existing test classes.

## Next step

Hand off `sprint-11-validation.md` to John for in-game validation. While John runs through scenarios, Sprint 12 implementation (Bot Stays Alive) can begin in parallel on its own branch.
