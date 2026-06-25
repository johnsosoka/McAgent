# McAgent Strategic Review — Synthesis, Ranking, Sprint Roadmap

**Date:** 2026-06-14
**Author:** AI Lead (orchestrator) — synthesizing six independent reviews
**Status:** Draft for John's review and architectural decision
**Inputs:**
- `autonomous-mode-architecture.md` (architect-planner)
- `command-mode-architecture.md` (architect-planner)
- `ux-design-review.md` (ui-design-expert)
- `architectural-code-review.md` (code-reviewer)
- `prior-art-research.md` (researcher, 14 systems surveyed)
- `observability-and-correctness.md` (own pass)

---

## 1. The Big Picture

Six independent reviewers, briefed on **non-overlapping angles**, converged on the same core diagnosis. That's a strong signal.

**The product vision:** a Minecraft bot that toggles between fully autonomous (hours unattended) and commanded (complex multi-step instructions).

**The core finding:** McAgent's existing architecture is excellent for what it was built for — short, reactive command-and-response — but the leap to autonomy is **categorical, not incremental**. Five seams need to be cut and re-stitched before the product vision is reachable. The good news: the cuts are localized, the seams are already in roughly the right place, and the work decomposes into ~5 independent sprints, each shippable on its own.

**The five seams** (each a workstream below):
1. **Memory** — the LLM's 20-message ring buffer is the single most-limiting line in the project.
2. **Plan/Goal abstraction** — the bot literally cannot represent "do this for 3 hours" today.
3. **Reflex layer** — survival can't wait for a 30-second Fireworks round-trip.
4. **Persistence** — nothing survives a disconnect; 3-hour runs are a non-starter.
5. **Operational legibility** — without counters, status, and hard-stop bypass, trust is impossible.

---

## 2. Cross-Review Convergence Map

| Finding | Autonomous | Command | UX | Code Review | Prior Art | Observability |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| Memory window destroys coherence on long runs | ✅ | ✅ | — | ✅ CRIT-1 | ✅ | ✅ MAJOR |
| Need Plan/Goal/Task abstraction | ✅ Tier 1 | ✅ G1 | (implied R7) | (implied) | ✅ #1 | — |
| No persistence across disconnect | ✅ Tier 2 | ✅ G2 | — | ✅ CRIT-2 | — | — |
| Missing survival/economy tools (eat/sleep/craft/smelt/chest) | ✅ Tier 2 | ✅ G3 | — | — | (implied) | — |
| Reflex/strategy split needed | ✅ Tier 1 | (implicit) | — | — | ✅ #5 | — |
| Hard-stop must bypass LLM | — | (Op C cancel) | ✅ R2 | — | — | ✅ Find 7 |
| Status query must bypass LLM | — | ✅ G8 | ✅ R4 | — | — | — |
| Mode model is too granular | — | — | ✅ R1 | — | — | — |
| Job heartbeat / progress visibility | — | (Op C frame msgs) | ✅ R3 | — | — | ✅ Find 3 |
| BotOperations is a god interface | — | — | — | ✅ MAJOR-1 | — | — |
| System prompt tool catalogue is a drift trap | — | — | — | ✅ MAJOR-2 | — | — |
| Race in processUrgentObservation | — | — | — | ✅ CRIT-3 | — | — |
| Outbound rate-limit bug (drainer/sender mismatch) | — | — | (R5 partial) | — | — | ✅ Find 1 (CRIT) |
| ClientThreadExecutor cascading timeouts | — | — | — | ✅ MAJOR-3 | — | — |
| Death/respawn undefined | ✅ Tier 1 | — | — | — | — | — |
| Per-tool post-conditions for self-verification | — | — | — | — | ✅ #3 | — |
| Explain-then-replan on failure | — | (deferred) | — | — | ✅ #4 | — |
| Episodic memory (three-factor retrieval) | ✅ Tier 2 | — | — | — | ✅ #2 | — |
| Skill library / Routines | ✅ Tier 3 | ✅ §7.3 P3 | — | — | ✅ Tier 2 #5 | — |
| Pre-execution approval for complex plans | — | — | ✅ R6 | — | — | — |
| Correction memory | — | — | ✅ R8 | — | (Reflexion) | — |
| Disambiguation gate | — | — | ✅ R9 | — | — | — |
| Voice / personality refinement | — | — | ✅ R13 | — | — | — |
| HUD / mod menu panel | — | — | ✅ R11 | — | — | — |
| Correlation IDs / structured logs / counters | — | — | — | (MINOR-5 testing) | — | ✅ Find 3,4 |
| Watchdog / liveness | — | — | — | — | — | ✅ Find 8 |
| Cost tracking | — | — | — | — | (Mindcraft per-role) | ✅ Find 3 |
| Read-only state cache (avoid client-thread block) | — | — | — | ✅ MAJOR-3 | — | — |
| Per-role model split | — | — | — | — | ✅ Tier 3 #10 | — |
| Tool schema auto-generation (drop catalogue) | — | — | — | ✅ MAJOR-2 | — | — |

**What this map tells us:**
- **Memory + Plan + Persistence + Reflex + Hard-stop** appear in 3+ reviews each. These are the load-bearing architecture changes.
- **Code review** surfaced the latent bugs nobody else was looking for (races, dispatcher hangs, dead `SafetyValidator`, `seenInWindow` leak, shadow-JAR fragility).
- **Prior art** mostly confirmed the architects' instincts (planner/executor split, three-factor memory, reflex/strategy split) — which is reassuring: we're not reinventing.
- **UX** is the only place where "what the player actually experiences" got a serious look. R1 (mode collapse) and R3 (heartbeat) are non-obvious without that lens.

---

## 3. Pooled Idea List — Deduped & Ranked

Sixty-three distinct items across six reports collapsed to 30 atomic recommendations. Each is tagged by:
- **Impact:** Critical / High / Medium / Low
- **Effort:** S (≤2 days) / M (≤1 sprint) / L (>1 sprint)
- **Dependency:** what must land first
- **Source(s):** which reviewers flagged it

### Foundation (must land first — everything else depends)

| # | Item | Impact | Effort | Depends on | Sources |
|---|---|---|---|---|---|
| F1 | **Tiered memory:** replace `MessageWindowChatMemory` with `SummarizingChatMemory` (working window + summary + system pin); fix `processUrgentObservation` double-injection | Critical | M | — | Auto, Cmd, CodeRev CRIT-1, PriorArt #2, Obs Find 2 |
| F2 | **Outbound chat correctness:** collapse drainer + sender rate limits into one gate; `send()` returns boolean; sender re-queues on rate-limit; make `lastSendTime` atomic | Critical | S | — | Obs Find 1, UX R5 (partial) |
| F3 | **Hard-stop + status bypass:** detect `stop/cancel/halt` in `FabricChatHandler` → direct `bot.cancel()` + queue drain; `agent status` → instant status string from new `JobTracker` | Critical | S | — | UX R2, R4, Obs Find 7 |
| F4 | **Operational instrumentation:** Counters bean (queues + LLM + framework buffer), JSONL run log, correlation ID at `InboundEvent` propagated via SLF4J MDC, `agent stats` chat command | High | M | — | Obs Find 3, 4, 5 |
| F5 | **30s watchdog / liveness:** detect dispatcher stall, log + single chat alert | Medium | S | F4 | Obs Find 8 |

### Core Abstractions (the bot can't be autonomous without these)

| # | Item | Impact | Effort | Depends on | Sources |
|---|---|---|---|---|---|
| C1 | **Plan/Goal abstraction + persistence:** typed `Plan`/`Step` sealed records; `TaskExecutor`/`JobSupervisor` thread sibling to existing dispatchers; `SessionState` JPA entity; restore on `initSpringContext()`; `submitPlan(...)` tool | Critical | L | F1 | Auto Tier 1, Cmd Op C, CodeRev CRIT-2, PriorArt #1 |
| C2 | **Split `BotOperations` into role interfaces:** Navigation/Observation/Inventory/Building/Control. `FabricBaritoneBridge` implements all. Consumers depend only on what they use | High | M | — | CodeRev MAJOR-1 |
| C3 | **Survival/economy tools:** `eat`, `craft`, `smelt`, `sleep`, `depositToChest`, `withdrawFromChest`, `returnHome`, `placeTorch`, `getWorldTime`, `isNight`, `getLightLevel`, `pickupNearbyItems` | High | M | C2 (clean place to land) | Auto Tier 2 #6, Cmd G3 |
| C4 | **Reflex layer:** deterministic BT (small in-house, ~200 LOC); priorities: safety > survival > goal pursuit > opportunism; runs on tick, never blocks on LLM | High | M | C3 (needs `eat`, `flee` available) | Auto Tier 1 #2 + Option C, PriorArt #5 |
| C5 | **Death/respawn handler:** Mixin or tick detection of `player.isDeadOrDying()`; framework event; record death location; suspend active goal | High | S | C1 (so goal *can* be suspended) | Auto Tier 1 #3 |

### Autonomy Unlock (delivers the product vision)

| # | Item | Impact | Effort | Depends on | Sources |
|---|---|---|---|---|---|
| A1 | **Three-mode model:** `standby` / `watch` / `auto`. Toggle responses echo full state. Internal: existing 4 axes become derived. | High | S | F3 | UX R1 |
| A2 | **LLM Planner role (GITM split):** decomposer AI-Service emits sub-goals; existing `Assistant` becomes executor on one sub-goal | High | M | C1, F1 | Auto Option C, Cmd Op C, PriorArt Tier 1 #1 |
| A3 | **Job heartbeat:** 1 message / minute on long jobs, suppressed if player interacted < 30s ago; structured format | High | S | F4 (counters), F3 (JobTracker) | UX R3 |
| A4 | **Named Routines:** parameterized macro templates stored in DB; `submitRoutine(name, params)` tool; hand-seed 3-5 (`returnHome`, `mineUntilFull`, `depositAndContinue`, `nightShelter`); allow `defineRoutine` from LLM | High | M | C1 (Plan exists), C3 (tools exist) | Cmd §7.3 P3, PriorArt Tier 2 #5 |
| A5 | **Tool post-conditions for self-verification:** annotate `@Tool` with expected state delta; surface in post-execution framework message | High | S | C1 (executor can check) | PriorArt Tier 1 #3 (GITM) |
| A6 | **Task-aware observer:** `AutonomousObserver` consults `TaskExecutor.currentMode()`; suppresses non-critical alerts during low-risk active task | Medium | S | C1 | Cmd G6 |

### Quality & Robustness (production-grade trust)

| # | Item | Impact | Effort | Depends on | Sources |
|---|---|---|---|---|---|
| Q1 | **Explain-then-replan on failure (DEPS):** structured failure → dedicated LLM turn for NL explanation → revised plan | High | M | C1, F1 | PriorArt Tier 1 #4 |
| Q2 | **Episodic memory (three-factor retrieval):** `AgentEvent` JPA + embedding; relevance + recency + importance; LLM-scored importance at write time | High | M | F1, C1 | Auto Tier 2 #4, PriorArt Tier 1 #2 |
| Q3 | **Pre-execution approval gate:** for plans > N steps or > 50 blocks, present plan and wait for `agent go` | High | S | C1, A2 | UX R6 |
| Q4 | **Correction memory:** auto-call `rememberNote(..., "correction")` when player corrects; retrieve at next session | Medium | S | F1 (so it survives), Q2 (so it's retrievable) | UX R8, PriorArt Reflexion |
| Q5 | **Fix `processUrgentObservation` race:** single atomic block over `addFrameworkContext` + `chat`; consider merging dispatchers into one priority executor | Critical | S | — | CodeRev CRIT-3, Obs Find 7 |
| Q6 | **Remove `<available_tools>` catalogue from system prompt:** rely on LangChain4j auto-discovery; richer guidance moves to `<*_guidance>` sections | High | S | — | CodeRev MAJOR-2 |
| Q7 | **Cache read-only world state on client tick:** `getNearbyEntities`, `getNearbyThreats`, `getNearbyPlayers`, `getHealthStatus`, `getInventorySummary` read from cache | High | M | — | CodeRev MAJOR-3 |
| Q8 | **`BotEventQueue` + concurrency tests** for dedup, priority, eviction, dispatcher races | Medium | M | — | CodeRev MINOR-5 |
| Q9 | **Disambiguation guidance for ambiguous commands:** system prompt classifies confidence; high → preview, medium → 1 question, low → 2 questions | Medium | S | (none, prompt only) | UX R9 |

### Polish, Hygiene, & Future

| # | Item | Impact | Effort | Depends on | Sources |
|---|---|---|---|---|---|
| P1 | **Voice/personality refinement:** terse, competent, structured progress; system prompt `<voice_guidance>` block | Medium | S | — | UX R13 |
| P2 | **Replace `BLOCK_MAP` with registry normalization** (delete dead code) | Low | S | — | CodeRev MINOR-3 |
| P3 | **Wire or delete `SafetyValidator`** (don't ship dead safety code) | Medium | S | — | CodeRev MINOR-4 |
| P4 | **Bound `seenInWindow` to capacity** in `FrameworkMessageBuffer` | Low | S | — | CodeRev MINOR-1 |
| P5 | **Shadow JAR relocation CI check:** scan output JAR for unshaded conflicting roots | Low | S | — | CodeRev MINOR-6 |
| P6 | **Per-role model split:** planner uses cheaper/faster model; executor/critic remain Kimi-K2 | Medium | S | A2 | PriorArt Tier 3 #10 |
| P7 | **Mod menu status panel (GUI):** keybind opens screen with mode toggle + current job + progress + recent events | High (UX) | L | F3 (JobTracker), A1 | UX R11 |
| P8 | **In-world job marker (beacon-style waypoint)** at active goal location | Medium | M | C1 | UX R12 |
| P9 | **Cost cap per session:** hard fail Fireworks calls past $X | Medium | S | F4 | Obs Open Q |
| P10 | **Skill library (Voyager-style vector retrieval):** higher tier than Routines; learned parameterized macros indexed by NL embedding | Medium | L | A4, Q2 | PriorArt Tier 2 #5 |
| P11 | **End-of-task reflection (Reflexion):** 2-3 sentence LLM postmortem written to episodic memory | Medium | S | Q2 | PriorArt Tier 2 #6 |
| P12 | **Wiki RAG for crafting recipes / mob behaviour** | Medium | L | Q2 | PriorArt MineDojo, JARVIS-1 |
| P13 | **Job queue (multiple sequential plans):** "do X, then Y" as explicit chaining | Medium | M | C1 | UX R14 |

---

## 4. Recommended Sprint Sequence

Five sprints. Each ships an in-game-testable improvement. No sprint blocks on the next.

### Sprint 11 — "Operations & Trust Foundation" (1–2 weeks)

**Goal:** Make the bot legible, stoppable, and free of silent message drops. Earn the right to ask players to trust longer runs.

**Scope:**
- F1 — Tiered memory + double-injection fix
- F2 — Outbound chat correctness
- F3 — Hard-stop + status bypass + `JobTracker` skeleton
- F4 — Counters + run log + correlation IDs + `agent stats`
- F5 — Watchdog
- Q5 — `processUrgentObservation` race fix
- Q6 — Remove tool catalogue from system prompt
- P1 — Voice refinement (cheap; sets tone for everything after)
- P2 / P3 / P4 — Small hygiene (BLOCK_MAP, SafetyValidator, seenInWindow)

**Outcomes:**
- The player can always stop the bot in <100ms.
- The player can always ask "what are you doing?" without LLM cost.
- Multi-hour runs have a run log we can grep.
- Tool catalogue no longer drifts.

**Risk:** Low. Mostly localized changes.

### Sprint 12 — "Bot Stays Alive" (1–2 weeks)

**Goal:** Survival reflexes + the missing physical tools. The bot stops dying when left alone. No new abstractions yet.

**Scope:**
- C2 — Split `BotOperations` into role interfaces (prerequisite for cleanly adding tools)
- C3 — Survival/economy tools: `eat`, `placeTorch`, `getWorldTime`, `isNight`, `pickupNearbyItems`, `getLightLevel` (the survival-critical subset; defer `craft`/`smelt`/`chest` to Sprint 13)
- C4 — Reflex layer skeleton: deterministic rules in `AutonomousObserver` for low food → eat, low health → flee, nightfall + no shelter → flee/place torches. No formal BT yet.
- C5 — Death/respawn handler + framework event
- Q7 — Read-only state cache on client tick

**Outcomes:**
- Unattended bot survives a night.
- Long runs don't melt the client thread.
- Bot reacts to threats without LLM latency.
- Adding tools no longer pulls everything into `BotOperations`.

**Risk:** Medium. Splitting `BotOperations` is mechanical but touches every consumer. Death detection has Mixin variance.

### Sprint 13 — "The Plan Layer" (2–3 weeks)

**Goal:** First-class plan + executor. The bot can be told "spend 3 hours doing X" and actually do it.

**Scope:**
- C1 — `Plan`/`Step` records, `TaskExecutor`, `JobSupervisor` thread, `SessionState` JPA entity, restore on init
- C3 (remainder) — `craft`, `smelt`, `depositToChest`, `withdrawFromChest`, `returnHome`
- A2 — LLM Planner role (decomposer/executor split, GITM-style)
- A3 — Job heartbeat
- A5 — Tool post-conditions surfaced in framework messages
- A6 — Task-aware observer
- Q3 — Pre-execution approval gate for complex plans
- Q8 — `BotEventQueue` + concurrency tests

**Outcomes:**
- "Mine 64 iron" is a persistent Plan that resumes after disconnect.
- "Build a starter base" presents a plan and asks for approval.
- Player can `agent status` and get step + progress.
- The LLM only re-engages on milestones / failures / new chat — token cost is bounded.

**Risk:** High — this is the biggest architectural change. Mitigations: ship Plan with only `Navigate`/`Mine`/`WaitFor` steps in Sprint 13; add `Build`/`Craft`/`SubPlan`/`InvokeRoutine` in Sprint 14.

### Sprint 14 — "Autonomy & Routines" (2 weeks)

**Goal:** The bot can be told `agent auto` and operate for hours.

**Scope:**
- A1 — Three-mode model collapse (`standby`/`watch`/`auto`)
- A4 — Named Routines (3-5 hand-seeded + `defineRoutine`)
- C4 (formalize) — Behavior Tree library (`Sequence`/`Selector`/`Action` nodes), refactor Sprint 12 reflexes into it
- Q1 — Explain-then-replan on failure
- Q9 — Disambiguation guidance
- P6 — Per-role model split
- P9 — Cost cap

**Outcomes:**
- `agent auto` works. Bot picks goals from the routine library when idle.
- Failures become recoverable instead of silent.
- Fireworks bill is predictable and capped.

**Risk:** Medium. Routines need real schema + library curation; everything else builds on Sprint 13.

### Sprint 15 — "Memory & Polish" (1–2 weeks)

**Goal:** Cross-session continuity. The bot learns.

**Scope:**
- Q2 — Episodic memory (three-factor retrieval, `AgentEvent` table)
- Q4 — Correction memory wired through Q2
- P11 — End-of-task reflection (Reflexion)
- P5 — Shadow JAR CI check
- P7 — Mod menu status panel (GUI) — *if* there's appetite; this is the only large-effort UX item

**Outcomes:**
- The bot remembers what worked / didn't last session.
- Corrections persist across restarts.
- (Optional) HUD for the user who wants ambient visibility.

**Risk:** Low. Patterns are well-documented.

### Backlog (Sprint 16+)

- P10 Skill library (Voyager-style learned skills)
- P12 Wiki RAG
- P13 Job queue (multiple sequential plans)
- P8 In-world job marker
- A2 extensions: failure-driven re-decomposition

---

## 5. Decisions Needed from John (Before Sprint 11)

These are **architectural decisions only John can make**. The team can't move past Sprint 13 without answers.

1. **Plan schema format.** Strict typed records (recommended — better validation, harder for LLM) vs. loose JSON (easier for LLM, harder to validate).
2. **Sprint 12's reflex layer — how aggressive in `watch` mode?** Should `watch` mode include auto-flee, or *only* alerts? My instinct: alerts only in `watch`; reflexes activate in `auto`.
3. **Persistent session scoping.** Per-server? Per-world? Same memory across all worlds? My instinct: per-server.
4. **Item recovery on death.** Should the bot try to return to the death location for dropped items? Risk vs. reward.
5. **Wiki licensing.** If we ever ingest the Minecraft Wiki (Tier 3 / backlog), CC-BY-NC-SA constraints matter for a public release. For personal use, no constraint.
6. **Sprint length / cadence.** John typically tests in-game before push. Are 1–2 week sprints with explicit testing gates the right rhythm?
7. **One concurrent Plan, or a queue?** My instinct: one at a time with explicit chaining via `agent then ...`. Queue is opt-in.
8. **Cost cap.** What's the per-session Fireworks budget? Hard cap or warning?

---

## 6. Risks & Anti-Patterns to Avoid

1. **Don't push the LLM onto the tick.** Tempting in autonomous mode. Will blow latency, cost, and reliability. Every successful prior-art system avoids this.
2. **Don't ship Sprint 13 (Plan layer) without Sprint 11's instrumentation.** Debugging a Plan-execution issue without correlation IDs in the log will eat days.
3. **Don't widen the chat memory window past ~30 messages.** The right answer is structured memory (F1, Q2), not bigger windows.
4. **Don't introduce LangGraph.** Our stack is LangChain4j (JVM). Stay there.
5. **Don't let Sprint 14 add features before Sprint 13 lands.** Routines + reflexes on top of a broken plan layer compound the failure modes.
6. **Don't autonomously re-plan on failure (yet).** DEPS-style explain → human approves → revised plan is the right v1. Pure autonomous re-planning is a known LLM cliff.
7. **Don't promise Voyager-style learned skills early.** P10 is real but expensive. Routines (A4) cover 80% of the value at 20% of the cost.

---

## 7. What Success Looks Like

**By end of Sprint 11:** John can run the bot for an hour, type `agent status` anytime, hit `agent stop` and get instant cancellation, and grep `run-*.jsonl` to figure out why anything happened.

**By end of Sprint 12:** John leaves the bot in a survival world overnight. He comes back to a bot at full health, fed, with a coherent log of the threats it dealt with.

**By end of Sprint 13:** John types `agent build a 7x5 oak cabin at my spot, then mine 64 iron`. The bot previews the plan, asks "agent go?", executes both steps over the next hour, survives a disconnect mid-mine, and reports `agent status` as `Mining IRON_ORE (41/64) | Y=-52 | Inv 78%`.

**By end of Sprint 14:** John types `agent auto`. Walks away for 3 hours. Returns to a bot that chose to gather food when hungry, slept through night, hauled a chest of cobblestone back to base, and logged a brief reflection on what it accomplished.

**By end of Sprint 15:** John starts a new session. The bot greets him with "Last time you corrected me on building too close to your house — I'll keep 8 blocks of clearance unless you say otherwise."

That's the product vision, decomposed.

---

## Appendix A — Architectural Convergence

All three architects (autonomous, command, prior-art) independently arrived at variations of the same shape:

```
                   ┌──────────────────────────────┐
                   │   LLM Strategic Layer        │
                   │   (Planner + Critic)         │
                   │   - decompose goals          │
                   │   - explain failures         │
                   │   - choose / authorize       │
                   └──────────────┬───────────────┘
                                  │  emits Plan/Goal
                                  ▼
                   ┌──────────────────────────────┐
                   │   Executor (deterministic)   │
                   │   - TaskExecutor / Goal Stack│
                   │   - persists SessionState    │
                   │   - emits framework events   │
                   └──────────────┬───────────────┘
                                  │  invokes tools
                                  ▼
                   ┌──────────────────────────────┐
                   │   Reflex Layer (BT)          │
                   │   - safety > survival > goal │
                   │   - tick-rate, sub-LLM       │
                   └──────────────┬───────────────┘
                                  │  delegates to
                                  ▼
                   ┌──────────────────────────────┐
                   │   BotOperations (split)      │
                   │   Nav/Obs/Inv/Build/Control  │
                   └──────────────────────────────┘
                                  │
                                  ▼  ClientThreadExecutor
                              Baritone
```

This is **the** architecture for autonomous LLM agents in embodied environments as of 2026. Voyager, GITM, JARVIS-1, Plan4MC, ReAcTree, mindcraft-ce all converge on it. We're not innovating — we're catching up. Which is the right place to be: the design space is well-mapped.

---

## Appendix B — Files Referenced

Architecture-shaping files the implementer will touch (full paths from individual reports):
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — system prompt
- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — split into role interfaces
- `core/src/main/java/com/mcagent/core/service/LangChain4jService.java` — race fix, planner-tick injection
- `core/src/main/java/com/mcagent/core/service/ChatService.java` — outbound rewrite
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — new tools + post-condition annotations
- `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` — replace `MessageWindowChatMemory`
- `core/src/main/java/com/mcagent/core/config/BotProperties.java` — autonomy + plan config
- `core/src/main/java/com/mcagent/core/CoreApplication.java` — new beans in `@Import`
- `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java` — reflex layer host, task-awareness
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` — dispatcher merge, JobSupervisor thread
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java` — bound `seenInWindow`
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — bypass commands (`stop`, `status`)
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatSender.java` — outbound contract collapse
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — implement new ops, death detection

New packages to create:
- `core/.../goal/` or `core/.../task/` — `Plan`, `Step`, `TaskExecutor`, `SessionState`, concrete steps
- `core/.../reflex/` — BT primitives
- `core/.../memory/episodic/` — `AgentEvent`, `EpisodicMemoryService`, reflection service
- `core/.../telemetry/` — `Counters`, `RunLog`, MDC helpers
- `core/.../routine/` — `RoutineDefinition`, seeded routines

---

## Appendix C — What Each Report Covers (Quick Index)

| Report | Read it for |
|---|---|
| `autonomous-mode-architecture.md` | Option C (goal stack + BT), 3-sprint phasing, 10 open questions on autonomy semantics |
| `command-mode-architecture.md` | Plan / Step / TaskExecutor sketch, Routines, 3 example command walkthroughs |
| `ux-design-review.md` | 3-mode model, R1-R14 ranked, journey maps, HUD mockup, voice guidance |
| `architectural-code-review.md` | 3 Critical + 3 Major findings, every latent bug we'd otherwise discover in production |
| `prior-art-research.md` | 14 systems mapped to our stack, 6 cross-cutting patterns, ranked steal-list |
| `observability-and-correctness.md` | Outbound bug, memory window, instrumentation gap, watchdog, correlation IDs |

Read in this order if you have time for only two: **architectural-code-review.md** (the latent bugs you'd hit first), then **this synthesis**.
