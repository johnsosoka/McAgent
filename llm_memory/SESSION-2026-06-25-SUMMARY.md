# Session Summary — 2026-06-25 — Strategic Review + 5 Sprints

**Status:** All 5 sprints implemented, tested, committed. Awaiting your in-game validation.

## What landed

| Sprint | Branch | Tests | Commit | Status |
|---|---|---|---|---|
| Foundation docs | `feature/sprint-11-foundation` | — | `7ccb998` | committed, not pushed |
| Sprint 11 — Operations & Trust | `feature/sprint-11-foundation` | 172 | `b2a8639` | committed, not pushed |
| Sprint 12 — Bot Stays Alive | `feature/sprint-12-survival` | 262 | `819dfb5` | committed, not pushed |
| Sprint 13 — The Plan Layer | `feature/sprint-13-plan-layer` | 409 | `942f858` | committed, not pushed |
| Sprint 14 — Autonomy & Routines | `feature/sprint-14-autonomy` | 486 | `f059c5f` | committed, not pushed |
| Sprint 15 — Memory & Polish | `feature/sprint-15-memory` | 528 | `077fb6e` | committed, not pushed |

**528 tests total. 0 failures. Shadow JAR builds and passes the new validator.**

## Branch state

```
feature/sprint-15-memory       <- HEAD (Sprint 15)
feature/sprint-14-autonomy
feature/sprint-13-plan-layer
feature/sprint-12-survival
feature/sprint-11-foundation
feature/issue-10-background-observation-loop  <- PR #15 still OPEN
main
```

Sprint branches stack on each other. When Issue #10 PR (#15) merges, rebase sprint branches onto main one at a time.

## Reading order for review

1. `llm_memory/strategic-review/README.md` → `SYNTHESIS-and-roadmap.md` — start here for the 5-sprint design.
2. `llm_memory/e2e-validation/sprint-NN-validation.md` — your in-game test procedures, one per sprint.
3. `llm_memory/sprint-NN/SPRINT-NN-COMPLETE.md` — per-sprint outcomes.
4. `llm_memory/logbook.md` — running session history.

## How to validate

For each sprint, follow `llm_memory/e2e-validation/sprint-NN-validation.md`:
- Build the shadow JAR: `./gradlew :fabric-mod:shadowJar`
- Copy `fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar` to your mods folder
- Restart Minecraft, join the test world
- Tail `latest.log` and `~/Library/Application Support/minecraft/logs/mc-agent/run-*.jsonl`
- Walk the scenarios

Recommend testing in order — Sprint 12 builds on Sprint 11, etc. The integration tests (S11.4 memory window, S12.9 overnight survival, S13.2 disconnect resume, S14.9 3-hour autonomy, S15.10 week-long preference learning) are where issues will surface.

## Architecture you now have

```
┌─────────────────────────────────────────────────────────────┐
│  Player chat (Mojang signed)                                │
│  ↓                                                          │
│  FabricChatHandler — bypasses for stop/status/stats/        │
│    standby/watch/auto/go/approved (all instant, no LLM)     │
│  ↓                                                          │
│  BotEventQueue                                              │
│  - inbound queue (priority + dedup + capacity + cmdId MDC)  │
│  - urgent dispatcher (sub-LLM safety reactions)             │
│  - job supervisor (Plan execution thread)                   │
│  - outbound drainer (rate-limited, peek/retry on limit)     │
│  ↓                                                          │
│  LangChain4jService.processInput / processUrgentObservation │
│  - LayeredChatMemory (pin + summary + working window)       │
│  - <memories> retrieval from EpisodicMemoryService          │
│  - <player_context> injection                               │
│  - atomic synchronized block (no double inject)             │
│  - cost tracking, latency counters                          │
│  ↓                                                          │
│  Assistant (executor) ↔ Planner (decomposer) ↔ PlanFailure  │
│  Explainer (critic) ↔ ReflectionService (postmortem)        │
│  - all use Kimi-K2; Sprint 14 P6 P9 split deferred          │
│  ↓                                                          │
│  TaskExecutor / JobSupervisor / SessionState (JPA)          │
│  - typed Plan / Step / WaitCondition records                │
│  - JSON polymorphism                                        │
│  - one-Plan-at-a-time CAS                                   │
│  - 5-min resume window after disconnect                     │
│  - approval gate (>3 steps OR >5min)                        │
│  - explain-then-replan on failure (DEPS, max 2 escalations) │
│  ↓                                                          │
│  AutonomousObserver (tick-driven)                           │
│  - Three-mode: STANDBY / WATCH / AUTO                       │
│  - SurvivalReflexes (HP/hunger/torch — sub-LLM, silent)     │
│  - DeathDetector                                            │
│  - Heartbeat (1/min on long jobs)                           │
│  - Task-aware threat suppression                            │
│  - AutoModeDriver (idle routine selection)                  │
│  - WorldStateCache (refreshed 5x/sec on client thread)      │
│  ↓                                                          │
│  BotOperations (5 role interfaces)                          │
│  ↓ ClientThreadExecutor                                     │
│  FabricBaritoneBridge → Baritone (pathfinding)              │
└─────────────────────────────────────────────────────────────┘
```

## Counters surfaced via `agent stats`

```
inbound.enqueued, inbound.dispatched, inbound.deduped, inbound.dropped
outbound.sent, outbound.rate_limited, outbound.dropped, outbound.discarded_disconnected
framework.published, framework.deduped, framework.dropped, framework.batched
llm.calls, llm.errors, llm.latency.ms.sum
llm.urgent.calls, llm.urgent.errors
llm.failure_explain.calls, llm.failure_explain.errors
tool.<name>.calls, tool.<name>.success, tool.<name>.errors
plan.submitted, plan.completed, plan.failed, plan.cancelled, plan.approved
cache.hit, cache.miss
reflex.flee, reflex.eat, reflex.torch, reflex.critical_no_threat
bot.deaths
heartbeat.sent
observation.suppressed
memory.corrections
reflection.success, reflection.failure
runlog.dropped
```

## Run log

Every event lands in `~/Library/Application Support/minecraft/logs/mc-agent/run-<startTimestamp>.jsonl`, one JSON line per event, with `cmd_id` correlation:

```jsonl
{"ts":"2026-06-25T17:30:00Z","cmd_id":"a3f2b9","event":"inbound_enqueued","player":"john","command":"mine 64 iron"}
{"ts":"2026-06-25T17:30:00Z","cmd_id":"a3f2b9","event":"llm_call","duration_ms":1820}
{"ts":"2026-06-25T17:30:02Z","cmd_id":"a3f2b9","event":"tool_call","tool":"decomposeAndExecute","duration_ms":12}
{"ts":"2026-06-25T17:30:02Z","cmd_id":"a3f2b9","event":"plan_submitted","task_id":"...","steps":1}
```

## Decisions you still owe (low priority — none block validation)

From `SYNTHESIS-and-roadmap.md` §5:
1. Plan schema format — **decided: strict typed records** ✓
2. Reflex aggressiveness in `watch` mode — **decided: alerts only, no auto-actions** ✓
3. Persistent session scoping — **decided: per-server** ✓
4. Item recovery on death — **decided: deferred** (no auto-return-to-death)
5. Sprint cadence — N/A (implemented all 5)
6. One Plan or queue — **decided: one-at-a-time** ✓
7. Cost cap — **decided: deferred to Sprint 15 follow-up** (P9 not shipped)
8. Wiki licensing — N/A (didn't ingest)

## Recommended next actions

1. **Validate Sprint 11 in-game** — most foundational, highest confidence.
2. **Push the simplest sprint first** for review. Sprint 11 has the cleanest scope and is the most isolated.
3. **File follow-up issues** during validation. The known deferrals (per-block chat silence UX R5, per-role models, cost cap, formal BT, async post-condition verification, GUI panel) are all documented in `SPRINT-NN-COMPLETE.md`.
4. **Don't run all 5 sprints stacked through real Minecraft simultaneously** — start with Sprint 11, validate, then layer.

## Anti-patterns I intentionally avoided

Per `SYNTHESIS-and-roadmap.md` §6:
- ✓ Didn't push LLM onto the tick.
- ✓ Sprint 11 instrumentation landed before Plan layer debug needs.
- ✓ Chat memory upgraded structurally (LayeredChatMemory + RollingSummaryService), not just widened.
- ✓ No LangGraph — stayed on LangChain4j.
- ✓ Sprint sequencing respected dependencies (11 → 12 → 13 → 14 → 15).
- ✓ Autonomous re-planning not auto-fire-and-forget — explain-then-replan still has a max of 2 escalations.
- ✓ Skill library deferred (Routines cover 80% at 20% of the cost).

## Files in this session

```
CLAUDE.md                                             # repo architecture guide
llm_memory/
├── SESSION-2026-06-25-SUMMARY.md                    # THIS file
├── logbook.md                                       # extended
├── session-continuity-notes.md                      # updated
├── strategic-review/
│   ├── README.md
│   ├── SYNTHESIS-and-roadmap.md                     # THE plan
│   ├── autonomous-mode-architecture.md
│   ├── command-mode-architecture.md
│   ├── ux-design-review.md
│   ├── architectural-code-review.md
│   ├── prior-art-research.md
│   └── observability-and-correctness.md
├── e2e-validation/
│   ├── README.md
│   ├── sprint-11-validation.md   # YOUR primary deliverable for in-game testing
│   ├── sprint-12-validation.md
│   ├── sprint-13-validation.md
│   ├── sprint-14-validation.md
│   └── sprint-15-validation.md
├── sprint-11/                                       # implementation briefs + bundle reports + SPRINT-11-COMPLETE.md
├── sprint-12/
├── sprint-13/
├── sprint-14/
└── sprint-15/
```

## Closing note

This session took the strategic vision (autonomous OR commanded multi-hour Minecraft bot) from the 6-report panel review through ~5 sprints of layered implementation. Every sprint landed with code review, follow-up fixes, and incremental test coverage growth. The architecture mirrors the 2026-state-of-the-art for embodied LLM agents (Voyager / GITM / JARVIS-1 lineage) translated to our LangChain4j + Baritone stack.

Hand off the e2e validation guides; the bot is ready to meet your world.
