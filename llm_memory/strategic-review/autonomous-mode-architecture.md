# Autonomous Mode Architecture — Strategic Review

**Date:** 2026-06-14
**Author:** AI Lead (Principal Architect role)
**Branch reviewed:** `feature/issue-10-background-observation-loop` (issues #3–#10 complete)
**Audience:** John Sosoka; future implementing agents (senior-python-engineer, devops-expert, etc.)

---

## Executive Summary

The McAgent codebase is in good shape as a **reactive command-and-response Minecraft bot**. The plumbing — Spring boot inside Fabric, three-channel event queue, framework-tagged memory injection, Baritone bridge via `ClientThreadExecutor`, tick-driven observer — is well-factored and ready to scale. However, the leap from "single-command bot" to "untended autonomous agent for hours" is a **categorical** one, not an incremental one. The current architecture has no concept of a persistent goal, no world model beyond what's in the LLM's 20-message ring buffer, no self-recovery, no death handling, and no continuous concerns (food, sleep, inventory pressure, day/night).

The single biggest architectural decision in front of the team is **how to structure the agent's executive function** — the thing that decides what to do next when no human is talking. I recommend a **hierarchical goal stack with an LLM Planner above a deterministic Behavior Tree / Reactive layer** (Option C below). The LLM is reserved for what it's uniquely good at — novel planning, ambiguity resolution, replanning after failure — and is kept off the hot path for moment-to-moment decisions (eat now, flee creeper, dodge lava) that have well-known correct answers.

The three highest-leverage gaps to address first are:
1. **No goal abstraction** — the bot literally cannot hold an intent across a single command.
2. **No persistent world model or episodic memory** — chat history is a 20-message ring buffer, lost on disconnect; the only durable state is named waypoints and free-text notes.
3. **No survival reflexes** — no eat tool, no death/respawn handler, no auto-flee, no torch placement; the bot will die within minutes of unattended play and never recover.

The right sequencing is: survival reflexes → goal abstraction → world model. Each is independently valuable and unblocks the next.

---

## Current State Audit

### What the architecture already gives us (and we should keep)

| Capability | Where it lives | Why it matters for autonomy |
|---|---|---|
| Clean port/adapter seam (`BotOperations` interface in `core/`, `FabricBaritoneBridge` in `fabric-mod/`) | `core/src/main/java/com/mcagent/core/service/BotOperations.java` | New tools don't require LLM/Spring changes — just an interface method + bridge impl + `@Tool`. Autonomy work won't fight the architecture. |
| Three-channel event queue | `fabric-mod/queue/BotEventQueue.java` | The `urgentDispatcher` thread is already the right insertion point for "the agent decided to act on its own." |
| Framework-tagged memory injection | `LangChain4jService.addFrameworkContext` | We can feed the LLM new context (world state, goal progress, failure reports) without it confusing them for player input. |
| Tick-driven observer | `fabric-mod/observer/AutonomousObserver.java` | The 1-Hz scan loop is the natural place to host a goal-selection tick and survival reflexes. |
| Spatial+temporal debounce | `AutonomousObserver.buildDebounceKey` | Pattern is reusable for any "observed N times in M seconds → notify" rule we add. |
| Persistent named locations | `core/memory/LocationMemoryEntry.java` + `LocationMemoryService` + vector store | Already JPA-backed and embedding-indexed. Foundation for semantic memory. |
| Material verification before action | `MinecraftTools.buildArea` (line 427) | Pattern of "check feasibility, send chat, then dispatch" is the right reflex pattern for the LLM. |

### What is structurally missing for long-horizon autonomy

| Missing capability | Evidence | Why it blocks autonomy |
|---|---|---|
| **Goal abstraction.** No `Goal`, `Plan`, `Intent`, or `Task` type anywhere in the codebase. | `grep -r "Goal\|Plan\|Intent\|Task" core/src/main` returns only Baritone `Goal*` imports. | The LLM holds one transient intent inside one `Assistant.chat()` call. As soon as that returns, the intent is gone except as text in chat memory. There is nothing for the observer or a planner to "resume." |
| **Persistent working memory.** Only the 20-message LangChain4j `MessageWindowChatMemory` in `LangChain4jConfig.chatMemory()`. | `application.yml` `bot.chat.max-history: 20`. Chat memory is in-memory only and wiped on `shutdownSpringContext()` (disconnect debounce after 60 ticks). | After ~10 player turns + framework messages the original instruction has fallen off the window. Across disconnects (dimension change, server restart) the bot forgets everything that wasn't a `rememberLocation`/`rememberNote` call. |
| **No world model.** Bot knows only what `getNearbyEntities`, `getNearbyThreats`, `getCurrentPosition`, `getInventorySummary` return *right now*. | `BotOperations` is entirely query-by-pull, no caching. | Cannot reason about "where did I last see iron?", "what does my base look like?", "which chest has wheat?". Every decision restarts from zero. |
| **No survival reflexes.** No eat tool, no auto-flee on low health, no sleep, no torch placement, no return-to-base. | `BotOperations` has no `eat`, `sleep`, `craft`, `smelt`, `depositToChest`, `withdrawFromChest`, `placeTorch`. The system prompt covers "be careful" but cannot actually heal the bot. | Hunger drops naturally, hostile mobs spawn at night, the bot will starve or die. |
| **No death/respawn handling.** No callback, no observer reaction. | Search for `onDeath`, `respawn`, `getRespawnPoint`: zero hits. `HealthStatus.isHealthy()` is the only check. | On death the LLM has no idea what happened, doesn't know where the items dropped, can't get back to where it was. Currently undefined behavior. |
| **No failure feedback loop.** A Baritone "path failed" lands in the framework buffer as text, but there is no structure ("retry N times", "give up after T seconds", "replan from a higher level"). | `FabricBaritoneBridge.handlePathEvent` fires raw strings. | An LLM that hallucinates `mineResource("AMETHYST_ORE", 64, 64)` gets a fail string and might just try again or give up — the *plan* doesn't know. |
| **No time-of-day awareness.** `BotOperations` exposes no `getWorldTime`, `isNight`, `isRaining`. | Confirmed by interface scan. | Cannot implement "build shelter before dark", "sleep through night", "wait out a storm". |
| **No continuous concerns model.** Hunger, inventory pressure (full), tool durability, light level, sleep cooldown are all unmodeled. | Confirmed by interface scan. | Continuous concerns are the *defining* difference between command-execution and autonomous operation. Without them the planner has nothing to weigh against the current goal. |
| **Active observer can't initiate work.** The system prompt explicitly says: *"Do NOT initiate mining or building from autonomous observations without player confirmation."* | `Assistant.java` line 125. | This is a sane guardrail today, but autonomous mode is precisely when we *do* want self-initiated work. The prompt and the observer's role need to bifurcate by mode. |

---

## Gap Analysis (ranked by leverage)

### Tier 1 — Blockers (without these, autonomy doesn't work at all)

1. **Goal abstraction + planner loop.** The system must be able to hold a top-level intent ("survive and gather 64 iron"), decompose it into subgoals, and re-evaluate at a fixed cadence. Today, the `BotEventQueue.dispatchLoop` only fires when a player speaks or when the observer publishes urgent. There is no autonomous tick that asks "what should I be doing right now?".

2. **Survival reflex layer (deterministic, sub-LLM).** Eating at low food, fleeing on low health, returning to a safe waypoint at dusk — these decisions must be sub-100ms and must not depend on an LLM round-trip (a Fireworks call is seconds, the LLM might hallucinate, and at night the bot will be dead before the response lands). This is a **Behavior Tree / Reactive layer** sitting between `AutonomousObserver` and the LLM. It uses the same `BotOperations` tools but is deterministic Java code with priorities.

3. **Death and respawn handling.** Hook the player death event (Mixin on `LocalPlayer#die` or via the existing tick observer detecting `player.isDeadOrDying()`). On death: record death location for item recovery, suspend current goal, on respawn re-initialize and decide whether to attempt item recovery vs. abandon. This is also where a `HomeWaypoint` becomes load-bearing.

### Tier 2 — Force multipliers (build these next)

4. **Episodic memory + persistent agent state.** A `AgentSession` JPA entity that survives disconnects: current goal stack, last known position, time of last food, recent failures with timestamps. Plus an `EpisodicEvent` log ("died to creeper at X,Y,Z at T", "completed `mine iron 64` in 23min") that the planner can RAG over.

5. **Semantic world model.** Extend `LocationMemoryEntry` with structured types beyond `LocationType.OTHER`: `BASE`, `FOOD_SOURCE`, `IRON_VEIN`, `WATER`, `DANGER_ZONE`. Auto-record findings: when the observer sees a cow herd, auto-tag the area; when mining returns iron, auto-tag the vein location. This turns the world from "what the LLM happens to remember" into "a queryable knowledge base."

6. **New tool surface for sustained living.** `eat(itemId|auto)`, `craftItem(recipe, qty)`, `smeltItem(input, fuel, qty)`, `sleep()`, `depositToChest(x,y,z,items)`, `withdrawFromChest(x,y,z,items)`, `placeTorch()`, `getWorldTime()`, `getLightLevel()`, `pickupNearbyItems(radius)`. These are *operational* tools — the planner will call them dozens of times per hour.

### Tier 3 — Polish (after the above ships)

7. **Skill/recipe library.** A registry of parameterized, tested behaviors ("strip-mine 16 blocks at depth Y from current position", "build 3x3 shelter from cobblestone"). Composable from `BotOperations`. Cuts LLM token cost dramatically and improves reliability.

8. **Learning from failure.** Episodic memory of "iron-vein at X,Y,Z exhausted after 18 blocks" or "creeper attacks frequent near (X,Z)" surfaced back into the planner's context window.

9. **Multi-player attribution.** If multiple humans give conflicting commands, whose intent wins? Today first-come-first-served via priority queue. Long-running autonomy will need a "current owner" concept.

---

## Architectural Options

I evaluated five candidate architectures for the executive-function layer. The five are not strictly comparable — A and B are too simple, D and E are too ambitious. I include them because the tradeoff space is genuinely the question being asked.

### Option A — LLM-as-Everything (status quo, extended)

Push the LLM harder. Add a "tick" framework message every N seconds that asks the LLM what to do. Add tools for food/craft/sleep. Trust the LLM to remember goals via chat memory.

- **Pros:** Minimal code change. Existing infrastructure already supports it. Maximum flexibility — the LLM can do anything we describe.
- **Cons:** LLM round-trips are 2-10s on Fireworks; a creeper kills the bot in 1.5s. The bot will die. Token cost goes through the roof if it's the executive every tick. Chat memory window is too small (20 messages) to hold a goal across an hour of operation. Hallucination of tool calls becomes catastrophic when there's no human to correct. No determinism — same situation might produce different actions.
- **Verdict:** Don't do this. It is the failure mode the rest of the options try to avoid.

### Option B — LLM-as-Planner, Single Active Goal

Introduce a `Goal` interface with a single `currentGoal` field on a new `GoalManager` bean. LLM produces a `Goal` from a player command; the executor runs it; observer can interrupt only for safety. On goal completion or failure, LLM re-plans.

- **Pros:** Simple mental model. One thing happening at a time. Easy to debug ("what's the current goal?"). Fits well with Baritone's single-goal model.
- **Cons:** No subgoal decomposition — "build 10x10 shelter and stock with food" is *not* one goal. The LLM has to either do the whole thing in one tool-call burst (it won't) or come back into the loop constantly (defeats the purpose). Can't handle continuous concerns *during* a goal — if the bot is mining and gets hungry, who decides to eat?
- **Verdict:** Worth building as a *stepping stone* if Option C is too ambitious in one sprint. Don't ship as the final architecture.

### Option C — Hierarchical Goal Stack + Behavior Tree (RECOMMENDED)

A two-layer executive:

1. **Strategic layer (LLM Planner).** Owns a stack of `Goal` objects. Top of stack is "current intent." On planner tick (every N seconds, or on goal completion / safety interrupt / failure), the LLM is asked: "Given current state X, current goal Y, and recent events Z, push/pop/replace goals." Output is a structured `PlannerDecision` (LangChain4j supports structured output).

2. **Tactical layer (Behavior Tree).** A deterministic priority tree evaluated every game tick (or every 20 ticks like the observer). Roughly:
   - Safety (health < 30%? → flee to nearest safe waypoint)
   - Survival (food < 6? → eat; night and no shelter? → flee/build)
   - Goal pursuit (delegate to the strategic layer's current goal's `step()` method)
   - Opportunism (within reach of a cow and hungry-soon? → kill it)

   Behavior tree nodes call `BotOperations` directly. LLM is *not* in this loop. This is what kills creepers in 1.5s.

- **Pros:** Clean separation of "what to do" (LLM) and "how to stay alive while doing it" (deterministic). Goals can be paused/resumed (push food gathering, pop back to mining). Subgoal decomposition is natural (build shelter = collect wood → craft planks → place blocks). LLM cost is bounded — it's called on milestones, not every tick. Failure has a structured place to land (current goal's `onFailure()` → planner re-evaluates).
- **Cons:** More code. Need to design `Goal` interface carefully (it must be both serializable to chat memory for the LLM and executable). Two layers means two places a bug can hide. Requires the team to define a small set of `Goal` types up front (`MineGoal`, `BuildGoal`, `GatherFoodGoal`, `SurviveGoal`, `IdleGoal`, `ComposedGoal`).
- **Verdict:** **This is the right design.** It cleanly accommodates both autonomous and commanded modes — "mine 64 diamonds" just pushes a `MineGoal` onto the stack; pure autonomous mode just means the planner is allowed to push goals on an empty stack. It respects what LLMs are good at and bad at.

### Option D — Pure GOAP / HTN Planner (no LLM in the loop except for command parsing)

Implement classical Goal-Oriented Action Planning: a set of `Action` definitions with preconditions and effects, a `WorldState`, and an A*-search planner that finds an action sequence achieving a goal state.

- **Pros:** Deterministic, debuggable, well-understood. No LLM hallucination. Cheap at runtime. Proven in games for 20+ years.
- **Cons:** All the autonomy intelligence is in the action definitions, which a human has to write. Cannot handle novel situations or ambiguous commands. The LLM was the *point* of this project — using it only to parse commands wastes its strengths. State-space explosion in Minecraft is real.
- **Verdict:** Don't choose this as the primary. *Borrow from it*: each `Goal` in Option C can internally use GOAP-style precondition checking (e.g., `BuildShelterGoal.preconditions()` returns "need 64 cobblestone, need pickaxe"). That gives the LLM/planner a cheap "can I do this now?" check without a round-trip.

### Option E — Multi-Agent LangGraph (LLM Specialists)

Multiple LLM agents — Planner, Surveyor, Builder, Survivalist — coordinated by a LangGraph state machine. Each agent has its own tool subset and prompt.

- **Pros:** Conceptually clean. Each agent has small focused context. LangGraph is in the stack already mentally (it's in the user's tech list).
- **Cons:** Massively more LLM cost and latency (each "decision" is now 2-4 round-trips). Inter-agent coordination is hard. Total overkill for a single Minecraft bot. LangChain4j (what we use) is not LangGraph (which is Python/JS); we'd need to port or call out.
- **Verdict:** Cool, wrong tool for this job. Revisit if/when the bot needs to coordinate with *other* McAgent instances (multi-bot deployments).

---

## Recommendations

### Primary recommendation: Option C, phased

Build the hierarchical goal stack + behavior tree, but **earn it in three sprints**:

**Sprint A — Survival reflexes & death handling (no goal abstraction yet).**
- Add `eat`, `pickUpItemsNearby`, `placeTorch` tools to `BotOperations` and `MinecraftTools`.
- Introduce a `SurvivalReflexes` bean (in `core/` so it's testable) called from `AutonomousObserver.scan()` *before* the threat/opportunity reporting. It runs deterministic rules: low food → call `eat`, low health → call `fleeFrom(nearest threat)`. No LLM involved.
- Add death detection (tick check on `player.isDeadOrDying()`) and a `DeathEvent` framework message. Add respawn observer that logs death location.
- Result: the bot survives unattended for the first time. The architecture has gained no new abstractions; we've just plugged the most embarrassing holes.

**Sprint B — Goal abstraction & manager.**
- Introduce `core/goal/Goal.java` (interface), `GoalStack.java` (bean), `GoalManager.java` (drives the planner tick).
- Implement 3-4 concrete goals: `IdleGoal`, `MineResourceGoal`, `GatherFoodGoal`, `ReturnToBaseGoal`.
- Add a `pushGoal(goal)` and `popGoal()` tool exposed to the LLM. Make `MineResource` and friends push a goal rather than directly invoke Baritone.
- The planner tick (in `AutonomousObserver.scan()`, every 5–10s, or on goal completion) wakes the LLM only when: top goal completed, top goal failed, safety reflex fired, or N seconds passed since last LLM tick. Use `LangChain4jService.processUrgentObservation()` with a new tag like `<planner_tick>` for these.
- Goal stack is persisted to a JPA entity `AgentSessionState` so disconnects don't lose intent.
- Result: bot can be told "mine 64 iron" and resume after a dimension change.

**Sprint C — Behavior tree formalization & autonomous mode.**
- Refactor `SurvivalReflexes` from Sprint A into a proper Behavior Tree (small library, ~200 LOC; don't pull in a framework). Nodes for `IfThen`, `Sequence`, `Selector`, `Action`. This is now the tactical layer.
- Add an `autonomous` toggle (similar to current `passive`/`active` observation modes). In autonomous mode, the planner is allowed to push goals on an empty stack — e.g., `IdleGoal` becomes `ExploreNearbyGoal` becomes `MineCoalGoal`, all without human input.
- Modify the system prompt: bifurcate behavior between commanded and autonomous mode using the existing framework-context pattern.
- Add `getWorldTime`, `isNight` tools so day/night drives the planner.
- Result: hours-long unattended operation is now possible.

### Secondary recommendation: persistent memory upgrade alongside Sprint B

`MessageWindowChatMemory.withMaxMessages(20)` in `LangChain4jConfig` is the single most limiting line of code in the project for autonomy. In Sprint B (when we already have JPA-persisted goal state), also:

- Introduce `EpisodicMemoryService` that persists every player command, every goal completion, every death, with embeddings.
- Replace the raw `MessageWindowChatMemory` with a custom `ChatMemory` impl that injects:
  - The recent N turns (current behavior, smaller N: 8–10).
  - The current goal stack as a system message.
  - Top-K retrieved episodic memories relevant to the current goal (RAG).

This is the "world model" gap. It's a lot of work but the existing vector store wiring (`VectorMemoryService`) does 80% of it already.

### What I would not do

- Don't try to do Sprints A, B, C in one go. Each Sprint produces an in-game-testable improvement; that's where the value of John's "test before push" workflow comes from.
- Don't introduce LangGraph just for this. LangChain4j is what's wired up and it's sufficient.
- Don't move the LLM onto the tick. Even on a fast model, $/hour and latency make this a non-starter.
- Don't extend the chat memory window past ~30 messages. The right answer is structured memory, not bigger windows.

---

## Open Questions

These should be answered before Sprint B starts. They are decisions for John, not the implementer.

1. **Autonomy authority.** When in autonomous mode and a player says "follow me", does that push a goal (preempt), replace the stack, or pause autonomy entirely? My instinct: push as `EventPriority.NORMAL`; explicit "stop everything" is a `CANCEL` clear.

2. **Goal serialization format.** Goals need to be representable in chat memory so the LLM knows what's going on. JSON? Compact custom string? My instinct: structured JSON inside a `<goal_stack>` framework tag, similar to existing `<player_context>`.

3. **What counts as "safe"?** The behavior tree needs a definition of a safe waypoint. Is it the home `BASE` location? Any `LocationType.OTHER`? Any lit area? My instinct: introduce `LocationType.SHELTER` and require the player (or autonomous build behavior) to designate one.

4. **Item recovery on death.** Should the bot attempt to return to its death location to grab dropped items? This is risky (whatever killed it may still be there) but high-value. My instinct: yes, but only after a delay (10 game minutes) and with a precondition check (health full, hostile mob count at death location = 0).

5. **Tool budget per LLM call.** Today there's no cap. An LLM in autonomous mode could call 50 tools in one turn. Need a max (5–10?) and a way to chain across calls. LangChain4j has hooks for this.

6. **Persistent identity across servers.** If John connects to two different servers, is it the same agent (shared memory) or two? Today it's effectively reset every disconnect. My instinct: scope `AgentSessionState` by `server_address + world_seed_hash` (if obtainable), default to per-server.

7. **How much should the behavior tree *talk*?** The current `chatService.send(...)` immediate-feedback pattern is great for player commands but spam for autonomous reflexes. My instinct: behavior tree actions are silent by default; only the planner / LLM speaks.

8. **Should crafting / smelting / chest interaction go through Baritone or a separate "interaction" layer?** Baritone's process model is built for pathfinding goals, not for "open inventory screen, click slot." We may need direct `Minecraft.getInstance()` manipulation in `FabricBaritoneBridge` for these. The seam is fine; the LOC will be ugly.

9. **Where does the planner live during a long Baritone path?** While Baritone is pathing for 90 seconds, should the planner tick? Today the inbound dispatcher is blocked inside `assistant.chat()` during that time. The urgent dispatcher is free. My instinct: planner tick runs on the urgent dispatcher; planner outputs are *commands to the goal stack*, not direct Baritone calls.

10. **Failure budget.** How many Baritone path failures before the goal is considered failed? How many goal failures before the planner gives up entirely and asks the human? These are simple constants but they shape user perception of bot competence.

---

## Quality Attributes Matrix

How the recommended design (Option C) addresses key quality attributes:

| Attribute | How Option C addresses it |
|---|---|
| **Reliability** | Behavior tree handles safety deterministically; LLM hallucination cannot kill the bot. Goal stack persists across disconnects. |
| **Observability** | Goal stack is a first-class entity — `getGoalStack` debug tool, framework messages on goal push/pop/complete/fail. Episodic memory provides a black-box recorder. |
| **Maintainability** | Two layers with clear contracts. New behaviors go in the BT; new high-level intents go in the goal library. LLM prompt stays small because tools are coarser-grained (goals, not low-level actions). |
| **Performance** | LLM is off the hot path. Tick decisions are O(depth-of-tree) deterministic. Token cost is per-milestone, not per-tick. |
| **Cost (LLM)** | Bounded by goal-completion frequency, not by uptime. Hours of operation might cost the same as today's 5-minute session. |
| **Developer experience** | Goal definitions are testable on Java 21 in `core/` against a mock `BotOperations`. Same for behavior tree nodes. No need to launch Minecraft to iterate. |
| **Reversibility** | Goal interface is the only new abstraction. If we abandon Option C for D later, the goals translate cleanly to GOAP actions. |

---

## Files referenced (absolute paths)

Architecture-shaping files the implementer will touch:
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/service/Assistant.java` — system prompt (mode-bifurcate)
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/service/BotOperations.java` — new tool methods (`eat`, `craft`, `smelt`, `sleep`, `depositToChest`, `getWorldTime`, etc.)
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/service/LangChain4jService.java` — planner-tick injection method
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — new `@Tool` methods; goal stack tools
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` — replace `MessageWindowChatMemory` with structured impl in Sprint B
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/config/BotProperties.java` — `AutonomyProperties` block
- `/Users/john/code/projects/McAgent/fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java` — host the planner tick and behavior-tree tick
- `/Users/john/code/projects/McAgent/fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — implement new `BotOperations` methods; death/respawn detection
- `/Users/john/code/projects/McAgent/fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` — possibly a fourth channel (planner tick) or reuse `urgentDispatcher`
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/CoreApplication.java` — register new beans (`GoalStack`, `GoalManager`, `SurvivalReflexes`, `EpisodicMemoryService`)

New packages to create:
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/goal/` — `Goal`, `GoalStack`, `GoalManager`, concrete goal types
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/reflex/` — `SurvivalReflexes`, behavior tree primitives
- `/Users/john/code/projects/McAgent/core/src/main/java/com/mcagent/core/memory/episodic/` — `EpisodicEvent`, `EpisodicMemoryService`, `AgentSessionState`

Reference docs to update at the end of Sprint C:
- `/Users/john/code/projects/McAgent/CLAUDE.md` — add Autonomy section
- `/Users/john/code/projects/McAgent/reference/minecraft-bot-architecture-spec.md` — incorporate goal/reflex layers
- `/Users/john/code/projects/McAgent/llm_memory/session-continuity-notes.md` — running notes per sprint

---

## TL;DR for the next session

- **Pick Option C** (hierarchical goal stack + behavior tree). Don't pick A, D, or E.
- **Sprint A first** — survival reflexes, eat tool, death/respawn. No new abstractions. Bot stops dying. Lowest risk, highest immediate value.
- **Sprint B** — goal stack + LLM planner. Bot can hold intent across disconnects.
- **Sprint C** — formal behavior tree + autonomous-mode toggle. Bot operates for hours.
- **Persistent memory upgrade** rides alongside Sprint B; it's the second-highest-leverage change after goals themselves.
- All planning, prompt-tuning, scaffolding done by AI Lead (orchestrator). All production code delegated to `senior-python-engineer` (Java is fine — same role) per global ops doctrine. `code-reviewer` gates each PR.
