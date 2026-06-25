# McAgent — Command-Mode Architecture Strategic Review

**Date:** 2026-06-14
**Author:** Architect (strategic review)
**Scope:** How McAgent should express, execute, observe, and recover from **complex, long-running, commanded** operations.
**Status:** Discussion draft — for John's review and architectural decision.

---

## 1. Executive Summary

McAgent today is a **single-turn tool-caller**: a player message goes through one LLM round-trip, the LLM picks one (or a few) `@Tool` invocations on `MinecraftTools`, each tool fires-and-forgets into Baritone, and the conversation ends. This is fine for "navigate to X" and even "mine some iron." It breaks down the moment the player says something with **duration, composition, parameterization, or recovery** in it:

- **Duration**: "spend 3 hours at the iron farm" — no `Tool` represents a time-bounded session, no scheduler enforces it, no progress is reported, and any subsequent player chat will yank the LLM's attention.
- **Composition**: "gather wood, then build a shelter, then sleep" — the LLM might verbally remember the plan, but there is no state machine, no resumption after restart, no guarantee step 2 starts when step 1 finishes (because the LLM's view of "finished" is async framework messages it may or may not act on).
- **Parameterization**: "operate the iron farm at coord X" — "iron farm" is a name with implied procedure. Today, that procedure exists nowhere. The LocationMemory only stores coordinates, not behaviors.
- **Recovery**: "Baritone got stuck / bot died / chunk unloaded" — the framework buffer surfaces the symptom as a `<framework>` message, but only on the *next* LLM turn. There is no autonomous re-planner.

The good news: the **seams are already in the right places**. `BotOperations` is a clean port. `BotEventQueue` has the inbound/outbound/urgent channels we need. `FrameworkMessageBuffer` already injects async game state into LLM memory. The bot can already cancel. What's missing is a **Task/Plan layer between the LLM and `BotOperations`** that owns the lifecycle of multi-step, long-running work.

**Recommendation in one line:** Introduce a `TaskExecutor` in `core/` driven by **LLM-generated declarative Plans** (sequence of typed Steps), persisted as state machines, with a `JobSupervisor` thread (sibling of `urgentDispatcher` in `BotEventQueue`) that ticks them forward and emits scoped framework messages. Skip true autonomous re-planning in v1 — when a step fails, hand control back to the LLM with structured failure context. Add a small library of **Named Routines** (parameterized macro templates stored in DB) for repeat patterns like "iron farm."

The three biggest gaps to close first, in order: (1) no Task object — durations and progress are unrepresentable; (2) no plan persistence — anything multi-step dies on disconnect; (3) tool inventory is missing the basic verbs (`craft`, `smelt`, `eat`, `sleep`, `depositToChest`) that make longer plans physically feasible.

---

## 2. Current State Audit

### 2.1 What the architecture does well

| Capability | Where it lives | Why it helps long tasks |
|---|---|---|
| Clean port/adapter at `BotOperations` | `core/src/main/java/com/mcagent/core/service/BotOperations.java` | A `TaskExecutor` can be added in `core/` without touching Minecraft. |
| Priority inbound queue with `CANCEL` precedence | `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java`, `EventPriority.java` | "Stop" already interrupts whatever's running. Pause/resume would slot here. |
| Framework message channel (throttled, dedup'd, batched) into LLM memory | `FrameworkMessageBuffer.java`, `LangChain4jService.addFrameworkContext()` | Long-running progress can be surfaced to the LLM without spam. |
| Urgent dispatcher (already used by `AutonomousObserver`) | `BotEventQueue.urgentDispatcher`, `triggerUrgentFramework()` | Pattern is proven for "wake the LLM mid-task." A `JobSupervisor` can use the same pattern. |
| Baritone process callbacks → progress events | `FabricBaritoneBridge.handlePathEvent / handleTick`, `setProgressCallback` | We get `AT_GOAL`, `CALC_FAILED`, mining/follow start-stop edges. This is the *raw signal* a Task object would consume. |
| ChatMemory window with `<framework>` and `<player_context>` tags | `Assistant.@SystemMessage`, `LangChain4jService` | LLM is already trained (in-prompt) to distinguish system events from player intent. |
| `cancelCurrentOperation()` tool wired to `BotOperations.cancel()` | `MinecraftTools.cancelCurrentOperation`, `FabricBaritoneBridge.cancel` | Half of pause/resume already exists; resume is a stub. |

### 2.2 What the architecture cannot do today

| Missing capability | Why it matters | Closest existing pattern |
|---|---|---|
| **Task object with lifecycle** (queued, running, paused, completed, failed) | Player can't query "what are you doing?" with structure; LLM can't reason about its own committed plan. | None — `BotOperations` calls have no return-time identity. |
| **Plan persistence across disconnect/restart** | Disconnect debounce already tears down the Spring context; any in-flight goal is lost. | Nothing — only `LocationMemory` and `PlayerNote` persist. |
| **Time-bounded execution** | "3 hours at the iron farm" has no representation. Baritone's `mineProcess` runs until inventory full or block exhausted, not until a wall-clock target. | `AutonomousObserver` ticks on wall-clock-ish intervals — that scheduling skeleton can be reused. |
| **Quantity-bounded execution beyond a single Baritone session** | `mineResource(IRON_ORE, 64, 64)` ends when those 64 are found *or* Baritone gives up. Continuing in a new chunk requires the LLM to notice and re-issue. | `IMineProcess` returns control; nothing re-issues it. |
| **Step sequencing with confirmation** | The system prompt asks the LLM to "wait for framework confirmation before proceeding" — but the LLM's "memory" is the chat window and framework messages are throttled/batched. Step N+1 may fire before N completes, or never. | The `<multi_step_guidance>` block in `Assistant.java` is aspirational, not enforced. |
| **Named, parameterized routines** | "the iron farm" implies a known procedure; today's tools are all primitives. | `LocationMemory` stores names → coords. No equivalent for procedures. |
| **Survival upkeep tools** | Long task → bot starves, dies, despawns equipment. No `eatFood`, `sleep`, `returnToBaseIfLowHealth`. | None. |
| **Inventory management mid-task** | Mining 3 hours fills inventory in 20 minutes. No `depositToChest`, no `craftItem`, no `smeltItem`. | None. |
| **Pause / resume that actually work** | `BotOperations.pause/resume` log "not supported" in `FabricBaritoneBridge`. | Goal-stash approach is feasible — needs implementation. |
| **Structured failure** | A failed `mine()` returns a `PathResult.success(false)` String; framework messages carry English. The LLM has to parse prose to recover. | `PathResult` enum exists but is shallow. |
| **Progress observation by player** (status command) | Player has no "what are you up to?" command that doesn't route through the LLM. | Toggle commands in `FabricChatHandler.handleToggleCommand` bypass the LLM — same pattern would work. |

### 2.3 Confused responsibilities, today

- **The LLM is the planner, the executor, and the bookkeeper.** Its only memory of "what I committed to" is the chat window. Once `<framework>Mining operation complete</framework>` is dedup'd or pushed out by the 20-message window, the commitment is gone.
- **Tool methods return immediately** with a confirmation string, but the *completion event* arrives via a different channel (`FrameworkMessageBuffer` → memory injection) at an indeterminate later time. The LLM has no way to *await* anything; it just gets nudged later.
- **`AutonomousObserver` is fine for reflexive reactions** (flee a creeper), but it has no concept that the bot might be **in the middle of an intentional task** that should override "flee from a skeleton 30 blocks away." Today, an active-mode observation can interrupt a half-finished build.

---

## 3. Example Walkthroughs (Three Commands, Traced End-to-End)

### 3.1 "Mine 64 iron ore"

**Today's flow:**
1. `FabricChatHandler.onChatMessage` → `BotEventQueue.enqueueCommand(NORMAL)`.
2. Dispatcher → `LangChain4jService.processInput` → `Assistant.chat`.
3. LLM calls `mineResource("IRON_ORE", 64, 64)`.
4. `MinecraftTools.mineResource` → `BotOperations.mine` → Baritone `IMineProcess.mine(64, BlockOptionalMetaLookup(IRON_ORE))`.
5. Tool returns immediately: `"Mining IRON_ORE (up to 64 blocks within 64 radius)"`. Chat goes out.
6. LLM finishes its turn with a closing message. **Conversation over.**
7. Minutes later, `FabricBaritoneBridge.handleTick` detects `wasMining && !isMining` → `notify("Mining operation complete.")` → framework buffer → eventually injected into LLM memory.
8. The next time the player talks, the LLM *might* mention it.

**Gaps revealed:**
- **No verification.** If Baritone found 47 ores and gave up (no more in range), `wasMining && !isMining` fires identically. Nothing checks "did we reach 64?"
- **No re-mining.** The LLM never resumes "find 17 more iron" unless the player notices.
- **Inventory blindness.** The bot might be full and silently dropping ore.
- **Progress invisible.** Player has no idea how it's going for 8 minutes.

### 3.2 "Spend 3 hours farming iron at coord (1200, 64, -800)"

**Today's flow:**
1. LLM gets the command, probably calls `navigateTo(1200, 64, -800)`.
2. Tool returns "Navigating to..." Conversation ends.
3. The "3 hours" and "farming iron" intent **evaporates from the system** once it's outside the 20-message memory window.
4. When the bot arrives, framework message says "Arrived at destination." The LLM, on the next player turn, has no record of what to do next.

**Gaps revealed:**
- **No durability of intent.** Wall-clock duration is unrepresentable.
- **No "iron farm" concept.** "Farming iron" at a coordinate could mean: AFK at a spawner-based farm, periodically run mine + return-to-chest loop, or strip-mine the area. The LLM has no template.
- **No scheduler.** Nothing wakes the LLM (or any sub-system) when 3 hours elapse.
- **No interruption rules.** If a creeper appears at the farm in active-observation mode, the bot will react — but should it "come back to the task" afterward? Today, no.

### 3.3 "Gather wood, then build a shelter, then sleep"

**Today's flow:**
1. LLM might in a single turn call: `mineResource("OAK_LOG", 16, 32)`, then... what? It can't really `buildArea` without knowing where, and it has no `craft` to turn logs into planks, no `sleep` tool, no `findShelterLocation`.
2. Best case: LLM calls `mineResource` and stops, planning to "do the rest" later — but later arrives as a framework message, possibly batched with others, and the LLM might be reacting to it long after the player has moved on.
3. Worst case: LLM calls all three in parallel; `buildArea` is called before any logs are gathered; `placeBlockAt` fails because there are no planks; bot tries to sleep at noon.

**Gaps revealed:**
- **No sequencing primitive.** "Then" is a semantic word with no execution counterpart.
- **No precondition checks.** The build step assumes materials; no plan step says "wait until inventory has ≥ N planks."
- **Missing tools:** `craft`, `sleep`, `findSafeShelterSpot`.
- **No idea of "done."** When is the shelter "built"? `buildArea` ends; does the plan advance? Nothing tells it to.

---

## 4. Gap Analysis (Prioritized)

| # | Gap | Severity | Estimated complexity | Notes |
|---|---|---|---|---|
| **G1** | **No Task / Plan abstraction.** Duration, sequencing, and progress are unrepresentable. | Critical | M-L | Foundation for everything else. |
| **G2** | **No plan persistence.** Tasks die on disconnect (3s debounce shutdown). | Critical | M | JPA entity + repository, reuse existing schema migration story. |
| **G3** | **Missing survival/economy tools.** `craft`, `smelt`, `eat`, `sleep`, `depositToChest`, `withdrawFromChest`, `returnHome`. | High | M each | No long task is realistic without these. |
| **G4** | **Pause / resume unimplemented.** `BotOperations.pause/resume` are stubs. | High | S | Cache current goal/process state; cancel; on resume re-issue. |
| **G5** | **Tool returns are unstructured.** Failure recovery requires the LLM to parse English. | Medium | S | Promote `PathResult` to a richer typed result; expose `taskId` in returns. |
| **G6** | **Observer can interrupt intentional tasks.** Active-mode flee-from triggers in the middle of a build. | Medium | S | Task-aware observer policy ("during BUILD task, ignore mobs > 12 blocks unless health < 50%"). |
| **G7** | **Named routines / parameterized macros.** "the iron farm" is a procedure, not a coordinate. | Medium | M | Build on `LocationMemory` — add `RoutineDefinition` table linked to a location. |
| **G8** | **Player has no out-of-band status command.** `agent status` should report active task without an LLM round-trip. | Low | S | Mirror `handleToggleCommand` pattern. |
| **G9** | **`AutonomousObserver` debouncing is location-only.** A creeper *next to* the bot during a 3h task should keep alerting. | Low | S | Add proximity-decay to debounce. |
| **G10** | **No re-planner.** When a step fails repeatedly, nobody escalates beyond "tell the LLM next turn." | Low (v1) | M | Defer — start with "fail → escalate to LLM with structured context." |

---

## 5. Sketches: What a Task / Plan Object Might Look Like

### 5.1 Plan and Step (high-level shape, not final API)

```java
// core/src/main/java/com/mcagent/core/task/Plan.java
public record Plan(
        UUID id,
        String summary,             // "Gather 32 oak logs, build a 5x4x3 shelter at (X,Y,Z), sleep"
        String requestedBy,         // player name
        Instant createdAt,
        List<Step> steps,
        TerminationPolicy termination
) {}

// A Step is a typed, declarative description of one unit of work.
// It is NOT a tool call directly — it's a recipe the TaskExecutor knows how to execute.
public sealed interface Step permits
        NavigateStep, MineStep, BuildStep, PlaceStep,
        CraftStep, SmeltStep, EatStep, SleepStep,
        DepositStep, WithdrawStep,
        WaitForStep,                // wait until condition (inventory has N items, time elapsed, etc.)
        InvokeRoutineStep,          // run a named, parameterized routine
        SubPlanStep                 // a nested Plan (for "do X, then Y" composition)
{
    String description();
    Duration timeout();             // hard ceiling; null = inherit from Plan
    RetryPolicy retry();            // 0..N retries with backoff
}

public record MineStep(
        String blockType,
        int quantity,               // 0 = unbounded (combine with TerminationPolicy.timeBounded)
        int searchRadius,
        Duration timeout,
        RetryPolicy retry
) implements Step { ... }

public record TerminationPolicy(
        Duration maxDuration,       // wall-clock cap on the whole Plan
        Predicate<WorldSnapshot> until,  // optional: stop when condition holds
        boolean stopOnLowHealth     // common safety knob
) {}
```

### 5.2 TaskExecutor (the engine)

```java
// core/src/main/java/com/mcagent/core/task/TaskExecutor.java
public interface TaskExecutor {
    /** Validates the plan, persists it, schedules execution. */
    TaskHandle submit(Plan plan);

    /** Current state of a running plan, including which step + step-progress. */
    Optional<TaskSnapshot> snapshot(UUID taskId);

    /** All running and queued plans, newest first. */
    List<TaskSnapshot> all();

    void pause(UUID taskId);
    void resume(UUID taskId);
    void cancel(UUID taskId);
}

public record TaskSnapshot(
        UUID id,
        Plan plan,
        TaskState state,            // QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
        int currentStepIndex,
        StepProgress stepProgress,  // e.g. "23/64 iron mined", "1m18s elapsed"
        Instant startedAt,
        Optional<FailureReason> lastFailure
) {}
```

### 5.3 Routine (parameterized macro)

```java
// core/src/main/java/com/mcagent/core/task/RoutineDefinition.java
@Entity
@Table(name = "routine_definition")
public class RoutineDefinition {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String name;            // "iron_farm"
    @Column(length = 4000)
    private String description;     // free-form; embedded for vector search
    @Column(columnDefinition = "TEXT")
    private String planTemplate;    // JSON: a Plan with ${parameters} placeholders
    private String parametersJson;  // declared parameter list ("location", "duration")
}
```

A routine is **invoked** by an `InvokeRoutineStep("iron_farm", {location: ..., duration: PT3H})`. The executor expands the template into concrete steps and executes them.

Routines can be **seeded** (a small library — return-home, deposit-and-resume-mine, mine-until-inventory-full, log-restock) and **LLM-generated** (the LLM can call a `defineRoutine` tool to save a procedure it just designed).

### 5.4 How the LLM creates a Plan

Two viable shapes:

- **Single `submitPlan` tool** with a structured argument (LangChain4j supports `@P` on rich types). LLM calls `submitPlan(Plan)` with a JSON-ish object; we parse, validate, and submit.
- **A small DSL of "plan builder" tools** — `beginPlan`, `addMineStep`, `addBuildStep`, `commitPlan`. More chatty, but the LLM gets immediate validation per step.

Recommend: **single `submitPlan` tool** plus a `submitRoutine(name, params)` shortcut. Less LLM chatter, one transaction.

---

## 6. Architectural Options for the Task / Plan Layer

I evaluated **five** options across two dimensions: *who decides the next step* (LLM vs. executor) and *how state is represented* (in chat memory vs. persisted object).

### Option A — Status quo: LLM as planner-executor (do nothing structural)

The LLM keeps planning in its head; we improve the system prompt and add the missing tools (`craft`, `eat`, etc.). Framework messages keep it informed; it re-plans every turn.

- **Pros:** Zero new infrastructure. Fastest to ship "more capability."
- **Cons:** Doesn't solve durability, doesn't solve duration, doesn't solve observability. As soon as the chat-memory window slides past, intent is gone. Disconnect = total amnesia. Multi-step plans remain unreliable.
- **Verdict:** Insufficient. Useful as a *bridging* step (add the tools first, even before the Task layer) but not a destination.

### Option B — Imperative scripting (in-LLM tool sequencing with `awaitTool`)

Add an `await` semantics: tools like `navigateToAndWait`, `mineUntilCount` block the LLM round-trip until the action completes (or times out), and return a structured result. The LLM writes a script-like sequence of awaited tool calls.

- **Pros:** Maps neatly to LangChain4j's tool-loop. The LLM directly expresses sequencing. Simple mental model.
- **Cons:** A 3-hour task blocks an HTTP request to Fireworks.ai for 3 hours. Even with massive timeouts, this is fragile, expensive (token cost for kept-alive context), and one network blip kills it. Pause/resume is incompatible with a blocked HTTP call. Player can't interrupt mid-await without dropping the LLM connection.
- **Verdict:** Acceptable for short multi-step (5-30 seconds), unacceptable for hours. Don't bet on this.

### Option C — LLM-generated declarative Plan + Executor (Recommended)

LLM emits a complete `Plan` (or invokes a named `Routine`) in one turn via `submitPlan(...)`. The LLM's job ends there. A `TaskExecutor` in `core/` owns execution: ticks steps forward, handles retries, sends scoped framework messages on milestones (step-start, step-progress every N seconds, step-end, plan-end). The LLM is only re-engaged on **failure** or **player chat**.

- **Pros:**
  - Clean separation: LLM does **strategy**, executor does **tactics**.
  - Plan is persistable → survives disconnect/restart.
  - Pause/resume/cancel/status are first-class executor APIs.
  - Player can ask "what are you doing?" without an LLM round-trip.
  - Token cost bounded — LLM only sees milestone framework updates, not the moment-to-moment.
  - Composable: Plans can contain SubPlans and InvokeRoutineSteps.
- **Cons:**
  - More moving parts. Need a Plan schema, a parser, a persistence layer, an executor thread.
  - LLM must learn a non-trivial JSON shape (mitigation: few-shot examples in system prompt, strict schema validation with a helpful error message tool that the LLM can react to).
  - Doesn't itself solve **autonomous re-planning** on failure — but it makes adding that a localized future change (a `PlanRevisionPolicy` slot on the executor).
- **Verdict:** **Recommended.** This is the sweet spot for McAgent's product vision. Aligns with where ports/adapters already are. The infrastructure (queue, framework buffer, urgent dispatcher) is a natural fit.

### Option D — BDI-style goal stack (Belief-Desire-Intention)

A "current intention" is always one of: an active Plan, a routine, or idle. Background "desires" (stay healthy, keep inventory free) can preempt the intention via priorities. Observations update beliefs; a tiny planner picks the highest-utility action each tick.

- **Pros:** Most aligned with classical game-AI patterns. Naturally handles "I should eat" interrupting mining without an LLM round-trip. Conceptually elegant.
- **Cons:** Major surface area. Requires a utility function, a belief base, conflict resolution. Years-deep rabbit hole. **Way over-engineered for current product stage.**
- **Verdict:** Defer. Worth revisiting once Option C is in production and we've learned which interrupt patterns repeat.

### Option E — LangGraph-style explicit graph

Represent the Plan as a LangGraph state graph (or a hand-rolled equivalent): nodes are steps, edges are transitions including failure paths. The LLM authors the graph; the executor walks it.

- **Pros:** Failure paths are first-class. Conditional logic ("if no iron found, navigate to alternate site") is explicit. Visualizable.
- **Cons:** LangGraph itself is Python; we'd hand-roll on the JVM. The added expressiveness vs. Option C's linear plan-with-retries is real but not yet *needed* — we have no evidence the simple-sequence model is the bottleneck. Adds substantial cognitive overhead for the LLM at the planning step.
- **Verdict:** Defer. Option C's `Plan` can be extended to a DAG later by adding `branches` to `Step` — it's not a one-way door.

### Summary table

| Option | Durability | Time-bounded | Sequenced | Observable | Resumable | LLM token cost | Complexity to build |
|---|---|---|---|---|---|---|---|
| A status quo | ❌ | ❌ | weak | weak | ❌ | medium | none |
| B in-LLM await | ❌ | ❌ | ✅ | weak | ❌ | **very high** | small |
| **C plan + executor** | ✅ | ✅ | ✅ | ✅ | ✅ | low | medium |
| D BDI | ✅ | ✅ | ✅ | ✅ | ✅ | very low | very high |
| E LangGraph DAG | ✅ | ✅ | ✅ | ✅ | ✅ | low | high |

---

## 7. Recommendations

### 7.1 Top recommendation: Option C — LLM-generated declarative Plan + TaskExecutor

**Why C over the others, in one paragraph:** It cleanly separates "what should the bot do" (LLM) from "how does the bot keep doing it for 3 hours" (executor) — a separation that *every* mature long-running agent system converges on (Temporal, Airflow, Step Functions, the planner/executor split in classical robotics). It fits McAgent's existing architecture like a glove: `BotOperations` is the substrate; `BotEventQueue` already has the thread topology; `FrameworkMessageBuffer` already injects scoped status into LLM memory. It is implementable in **phases without breaking changes** to today's tool catalogue. And it doesn't paint us into a corner — Options D and E remain reachable later by extending `Plan` and `TaskExecutor`.

### 7.2 Secondary recommendation: Build the missing physical tools *first*, in parallel with planning

Before — or while — building the executor, ship `craft`, `smelt`, `eat`, `sleep`, `depositToChest`, `withdrawFromChest`, `returnHome`. Reasons:

- These are valuable on their own — even the status-quo single-turn LLM benefits.
- They're prerequisites for any non-trivial Plan to be **physically executable**. Building the Plan engine before the building blocks would leave us with elegant orchestration of impossible procedures.
- They expose any new categories of "must run on client thread" or "must wait on inventory tick" issues, so we discover them before they complicate executor design.

### 7.3 Recommended phasing (rough sequencing, not a sprint plan)

1. **Phase 0 — Tooling completeness** (1 sprint): Add `eatFood`, `sleep`, `depositToChest`, `withdrawFromChest`, `craft`, `smelt`, `returnHome`. Implement `BotOperations.pause()/resume()` for real (cache and re-issue the current goal). Add an out-of-band `agent status` toggle command that reports raw Baritone state ("Mining iron, 23 found, 41s active"). Promote `PathResult` to carry a structured `failureCode` enum.
2. **Phase 1 — Task layer skeleton** (1 sprint): Introduce `Plan`, `Step` (subset: `Navigate`, `Mine`, `WaitFor`), `TaskExecutor`, `TaskSnapshot`. JPA persistence. A `JobSupervisor` thread sibling to `urgentDispatcher` in `BotEventQueue` that ticks the active task. One new tool: `submitPlan(Plan)`. System prompt teaches the LLM the JSON shape.
3. **Phase 2 — Composition & survival** (1 sprint): Add remaining Step types (`Build`, `Craft`, `Smelt`, `Eat`, `Sleep`, `Deposit`, `Withdraw`, `SubPlan`). Add `TerminationPolicy` (max duration, low-health stop). Wire `AutonomousObserver` to consult `TaskExecutor.snapshot()` so it knows when to *not* interrupt a benign nearby zombie.
4. **Phase 3 — Routines** (1 sprint): `RoutineDefinition` entity + `submitRoutine(name, params)` tool + `defineRoutine` tool so the LLM can save procedures. Seed the library with 3-5 hand-written routines (`returnHome`, `mineUntilFull`, `depositAndContinue`, `nightShelter`).
5. **Phase 4 — Failure escalation** (1 sprint): Structured failure context routed through urgent dispatcher → LLM with a synthetic prompt "Plan X step Y failed because Z; either submit a revision (`revisePlan(taskId, ...)`) or cancel."

Each phase ships independently and leaves the bot more capable. No phase forces a re-write of an earlier one.

### 7.4 Specific design choices I'd lock in early

- **Plan steps are typed records, not free-form JSON.** Validates at submit-time, gives the LLM a constrained grammar, integrates cleanly with LangChain4j tool argument binding.
- **Executor runs on a dedicated thread** (`mc-agent-job-supervisor`), separate from inbound/urgent. Steps that touch Baritone still go through `ClientThreadExecutor` — the executor thread is just orchestration.
- **One active Plan at a time.** Queuing multiple Plans invites accidental complexity (priority, preemption, deadlocks). Make "queue a second Plan" an explicit player action ("agent then..." or `submitPlan(after=<id>)`). Most product use cases are sequential.
- **Framework messages from the executor get a step-scoped prefix:** `<framework>[task: mine 64 iron, step 1/3] 23/64 mined, 1m12s elapsed</framework>`. This gives the LLM a stable identifier to reference and reason about.
- **Cancel is non-negotiably cooperative.** Executor checks a cancel flag between steps and on every step's progress callback. Baritone `cancelEverything()` is called on cancel of the active step.
- **Persistence is "rough state, not exact":** persist the Plan, the current step index, the wall-clock budget remaining. On reconnect, the LLM is notified via framework message: `"Resumed task X at step 2/4 after 3m disconnect."` Don't try to checkpoint Baritone's internal pathfinder state — re-pathing from current position is fine.
- **`AutonomousObserver` becomes Task-aware** via a small `TaskExecutor.currentMode()` query: `IDLE`, `RUNNING_LOW_RISK`, `RUNNING_HIGH_RISK`. Observer suppresses non-critical observations during `RUNNING_LOW_RISK`.

### 7.5 What not to build (yet)

- **Don't build automatic LLM re-planning.** When a step fails, surface a structured failure to the LLM and let the human decide between "agent retry," "agent skip," and "agent abandon." Autonomous re-planning is a known cliff for LLM agents (they confabulate solutions). Earn the right to add it once we have telemetry on which failures are routine.
- **Don't build a "skill learning" subsystem.** Routines are *written* (by humans or by the LLM at the player's direction), not *learned* from observation. Learning is a research problem; routines are an engineering one.
- **Don't promise resumable Builds.** Baritone's `IBuilderProcess` against a `FillSchematic` is idempotent enough that "cancel and re-issue with the same coords" works. Don't try to track partial progress block-by-block in v1.

---

## 8. Quality Attributes Implications

| Attribute | How Option C addresses it |
|---|---|
| **Scalability** | One concurrent Plan keeps the model simple. The DB pressure of one Plan-per-session is trivial. LLM calls per task drop dramatically (one to plan, one per failure). |
| **Reliability** | Plan persistence + structured failure codes mean the bot can resume after disconnect and the LLM can reason about specific error categories. |
| **Observability** | `TaskSnapshot` powers an `agent status` chat command, structured logs, and future UI. Framework messages stay throttled but become semantically richer with step context. |
| **Safety** | `TerminationPolicy.stopOnLowHealth`, plus a Task-aware observer, mean a long-running task can defend itself without the LLM in the loop. |
| **Developer experience** | Adding a new Step type = new record + executor case + system prompt example. Comparable cost to adding a new `@Tool` today, with better testability (executor logic is pure JVM in `core/`). |
| **Testability** | `TaskExecutor` lives in `core/`. Use a fake `BotOperations` to test multi-step Plans deterministically. Currently the only end-to-end testable layer is the LLM tool catalogue. |

---

## 9. Open Questions for John

1. **One Plan at a time, or a queue?** I'm recommending one-at-a-time with explicit chaining. Do you want the bot to *automatically* accept "do X, then go do Y next" as queued? (My take: no — too easy to forget what's pending. Make queuing explicit.)
2. **How aggressive should "Task-aware observer" be?** During a 3-hour mining run, do you want zero interruption from passive mobs and only "creeper within 8 blocks" from hostiles? Or a player-configurable interrupt threshold per Plan?
3. **Routine authoring path.** Should routines be:
   (a) Hand-authored Java/YAML, committed to the repo (boring, reliable);
   (b) LLM-defined via `defineRoutine` tool, persisted to DB (flexible, risk of bad routines);
   (c) Both, with hand-authored as the "trusted" library and LLM-defined as user-scoped?
   My lean: (c), with hand-authored seeded at startup.
4. **Failure escalation prompt.** When the executor hands a failure back to the LLM, should it (a) wait for the player to notice and ask, (b) proactively send a chat message ("I hit a snag, want me to keep trying?"), or (c) auto-retry N times silently first?
5. **Plan JSON: how strict?** Strict typed records (mine recommendation) require the LLM to learn one schema. A looser JSON shape is easier for the LLM but harder to validate. Want me to draft a concrete schema we test against Fireworks first?
6. **What about creative/spectator vs. survival?** Several recommended tools (`eat`, `sleep`) are only meaningful in survival. Should the executor introspect game mode and skip survival-only steps gracefully?
7. **In-game UI?** A dedicated HUD widget showing "Task: mine 64 iron, 23/64, 4m12s in" would be a huge UX win. Out of scope for the architecture, but worth thinking about now so the `TaskSnapshot` shape is HUD-friendly.
8. **Cost / token budget for plan generation.** A complex Plan in JSON could be hundreds of tokens. Worth a once-per-task cost? (I think yes — replaces dozens of single-turn calls today.)

---

## 10. References to Specific Files and Classes

| Concern | File | Notes |
|---|---|---|
| LLM contract | `core/src/main/java/com/mcagent/core/service/Assistant.java` | System prompt needs sections for `<plan_submission>`, `<routine_invocation>`, and updated `<multi_step_guidance>`. |
| Tool catalogue | `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` | Add `submitPlan`, `submitRoutine`, `defineRoutine`, plus the survival/economy tools (`eatFood`, `sleep`, etc.). |
| Bot port | `core/src/main/java/com/mcagent/core/service/BotOperations.java` | Extend with `eat()`, `sleep()`, `interactWithChest()`, `craft()`, `smelt()`. `pause()/resume()` need real implementations. |
| Baritone adapter | `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` | Implement the new `BotOperations` methods. Cache current `Goal` for pause/resume. Promote progress events into structured envelope. |
| Threading | `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` | Add a `jobSupervisor` executor alongside `inboundDispatcher` and `urgentDispatcher`. Mirror lifecycle in `start/shutdown`. |
| Framework messages | `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java` | No change required, but consider adding a `publish(taskId, message)` overload to enable scoped prefixes. |
| Memory injection | `core/src/main/java/com/mcagent/core/service/LangChain4jService.java` | New `processFailureEscalation(TaskFailure)` mirroring `processUrgentObservation`. |
| Persistence model | `core/src/main/java/com/mcagent/core/memory/` (new sibling package `task/`) | New entities `PlanEntity`, `StepEntity`, `RoutineDefinitionEntity`. Reuse the JPA + Flyway pattern. |
| Observation policy | `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java` | Inject `TaskExecutor` reference; consult `currentMode()` before publishing. |
| Spring wiring | `core/src/main/java/com/mcagent/core/CoreApplication.java` | Register new beans in `@Import`. |
| Chat handler | `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` | Add `agent status`, `agent pause`, `agent resume` to `handleToggleCommand` so they bypass the LLM. |

---

## Appendix A — Example Plan JSON (illustrative)

```json
{
  "summary": "Spend up to 3 hours farming iron at the iron farm; deposit when full; eat if hungry; come home if low health.",
  "termination": { "maxDuration": "PT3H", "stopOnLowHealth": true },
  "steps": [
    {
      "type": "InvokeRoutine",
      "routine": "iron_farm",
      "params": { "location": { "x": 1200, "y": 64, "z": -800 } },
      "timeout": "PT3H"
    }
  ]
}
```

Where the `iron_farm` routine template might decompose to:

```json
{
  "steps": [
    { "type": "Navigate", "to": "${location}" },
    {
      "type": "WaitFor",
      "condition": { "kind": "InventoryHas", "item": "minecraft:iron_ingot", "count": 64 },
      "timeout": "PT20M",
      "onTimeout": "advance"
    },
    { "type": "InvokeRoutine", "routine": "deposit_and_resume" },
    { "type": "Loop", "until": "${terminate}" }
  ]
}
```

(Final shape TBD; this is conceptual.)
