# Sprint 13 — The Plan Layer

**Branch:** `feature/sprint-13-plan-layer` (off Sprint 12)
**Goal:** First-class typed `Plan` + `TaskExecutor`. Bot can be told "spend 3 hours doing X" and actually do it. Plans persist across disconnect.
**Source:** `llm_memory/strategic-review/SYNTHESIS-and-roadmap.md` §4 Sprint 13 + `command-mode-architecture.md`
**Validation:** `llm_memory/e2e-validation/sprint-13-validation.md`

## Items

### C1 — Plan/Step records + TaskExecutor + SessionState persistence

**New package: `core/src/main/java/com/mcagent/core/task/`**

#### Plan & Step records
```java
public record Plan(
        UUID id,
        String summary,
        String requestedBy,
        Instant createdAt,
        List<Step> steps,
        TerminationPolicy termination
) {}

public sealed interface Step permits
        NavigateStep, MineStep, BuildStep, PlaceStep,
        EatStep, SleepStep, CraftStep, SmeltStep,
        DepositStep, WithdrawStep, WaitForStep,
        InvokeRoutineStep, SubPlanStep {
    String description();
    Duration timeout();
    RetryPolicy retry();
}

public record TerminationPolicy(
        Duration maxDuration,
        boolean stopOnLowHealth
) {}

public record RetryPolicy(int maxRetries, Duration backoff) {}
```

For Sprint 13 ship only: `NavigateStep`, `MineStep`, `WaitForStep`. Sprint 14 adds the rest.

#### TaskExecutor + JobSupervisor thread
```java
public interface TaskExecutor {
    TaskHandle submit(Plan plan);
    Optional<TaskSnapshot> snapshot(UUID taskId);
    Optional<TaskSnapshot> currentTask();
    void cancel(UUID taskId);
}
```

Implementation: `core/src/main/java/com/mcagent/core/task/DefaultTaskExecutor.java`.
- One-Plan-at-a-time semantics (per SYNTHESIS §6 design choice).
- Submit puts the plan in `RUNNING` state, persists `SessionState`, kicks off step execution.
- `JobSupervisor` thread (single-thread executor named `mc-agent-job-supervisor`) ticks the current step:
  - For `NavigateStep`: delegates to `bot.navigateTo(x,y,z)`; polls completion via state cache `currentPosition` vs target.
  - For `MineStep`: delegates to `bot.mine(...)`; polls Baritone via existing progress callback path.
  - For `WaitForStep`: blocks until condition satisfied (e.g. `InventoryHas(item, count)`).
- On step complete: advance index, persist `SessionState`, fire framework message `<framework>[task:summary][step n/N] done</framework>`.
- On step fail/timeout: structured `TaskFailure` event, call `langChainService.processFailureEscalation(...)` (new method to add). For Sprint 13 v1: explain-then-replan is **deferred to Sprint 14 Q1**. Sprint 13 just halts plan and pings the LLM with a structured failure context.

#### SessionState JPA
```java
@Entity
@Table(name = "session_state")
public class SessionState {
    @Id private UUID taskId;
    @Column(columnDefinition="TEXT") private String planJson;
    private TaskState state;
    private int currentStepIndex;
    private Instant startedAt;
    private Instant updatedAt;
    @Column(columnDefinition="TEXT") private String lastFailureReason;
    private String requestedBy;
}
```

- `SessionStateRepository extends JpaRepository<SessionState, UUID>`.
- Write on every state change.
- On `McAgentFabricMod.initSpringContext()`: query `findFirstByStateAndUpdatedAtAfter(RUNNING, Instant.now().minusMinutes(5))`. If found, restore the Plan and resume.

#### Wiring
- New thread in `BotEventQueue` (sibling to inbound/urgent dispatchers): `jobSupervisor`. Lifecycle: started in `start()`, shut down in `shutdown()`.
- `CoreApplication.@Import` adds the new beans.
- `FabricModConfig` injects `BotOperations` into the executor.

#### New tool: `submitPlan`
On `MinecraftTools`:
```java
@Tool("Submit a structured Plan to be executed by the bot. The Plan is a sequence of Steps. The bot executes Steps in order, tracking progress and reporting milestones.")
public String submitPlan(
        @P("Summary of what this plan does") String summary,
        @P("JSON-encoded list of steps") String stepsJson) {
    // parse stepsJson with Jackson, validate, build Plan
    // call taskExecutor.submit(plan)
}
```

For Sprint 13: Plan submission is **optional** — single-tool calls still work for backward compat. The system prompt (modified) tells the LLM: "For tasks requiring more than one tool call or > 30s, prefer `submitPlan` over chained tool calls."

### A2 — LLM Planner role

**New file:** `core/src/main/java/com/mcagent/core/service/Planner.java`. LangChain4j AI-Service:
```java
public interface Planner {
    @SystemMessage("...")
    PlanDraft propose(String playerRequest);
}
```

The `Planner` system prompt is short and focused: "Decompose the player request into 1-N typed Steps. Output JSON matching the `PlanDraft` schema. Each step must have a clear success criterion."

`PlanDraft` is a record with the steps. The executor validates and turns it into a Plan.

**Wiring:** `Planner` becomes a tool the `Assistant` can call: `@Tool decomposeAndExecute(String playerRequest)` — calls `Planner.propose(...)`, then `taskExecutor.submit(...)`.

**For Sprint 13 v1**, the model is the same as the Assistant (Kimi-K2). Sprint 14 P6 splits per-role.

### A3 — Heartbeat

**File:** `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java`.

Add heartbeat tick: every observer scan, check if `taskExecutor.currentTask()` exists AND no player chat in the last 30s AND it's been ≥60s since the last heartbeat → publish a heartbeat outbound message via `BotEventQueue.enqueueOutbound`.

Format (terse, structured): `Mining: 47/200 IRON_ORE | Y=-32 | Inv 36%`. Pull from `JobTracker` + state cache.

### A5 — Tool post-conditions

**New annotation:** `@PostCondition` on tool methods (or store as a `@Tool` description suffix). For now use a `Map<String, PostCondition>` keyed by tool name maintained in `MinecraftTools`. Each post-condition is a `Function<WorldStateSnapshot, Boolean>` that checks whether the world reflects the expected change.

Example: `mineResource` → "inventory of {blockType} increased by `quantity`".

After tool execution, `JobSupervisor` runs the post-condition with the post-tool world snapshot. The framework message becomes:
`<framework>tool:mineResource done. expected: +4 IRON_ORE. observed: +3 (1 dropped, out of range).</framework>`

### A6 — Task-aware observer

**File:** `AutonomousObserver.java`. Inject `TaskExecutor`. Before publishing a threat observation:
- If no active task → publish normally.
- If active task with `riskLevel == LOW` (default) → only publish threats within `lowRiskThreatRadius` (default 8).
- If `riskLevel == HIGH` (manually set, e.g. during combat) → publish all threats.

`riskLevel` is part of `TaskSnapshot`. Default LOW. Players can override per-task (deferred to Sprint 14).

### Q3 — Pre-execution approval gate

**File:** `MinecraftTools.submitPlan(...)` adds a complexity check:
- If `steps.size() > 3` OR estimated wall-clock > 5 min OR build volume > 50 blocks → DO NOT submit. Instead:
  - Store the Plan as `PENDING_APPROVAL` in `SessionState`.
  - Send a chat message with the plan preview.
  - Await a player command starting with `agent go`, `agent approved`, or `agent change ...`.
- Add a new bypass in `FabricChatHandler` for `agent go`/`agent approved`: load the pending plan, call `taskExecutor.submit(...)`, ack.

### Q8 — Concurrency tests

**File:** `fabric-mod/src/test/java/com/mcagent/fabric/queue/BotEventQueueTest.java`.

Tests:
- Dedup within window (rapid duplicate enqueue → only one event dispatched).
- Priority ordering (CANCEL beats NORMAL).
- Capacity eviction (9th enqueue evicts oldest).
- Concurrent urgent + inbound dispatch (no chatMemory corruption — re-test the F1 race fix).
- Shutdown while dispatching: no NPE, no leaked threads.

## Sequencing

1. C1 (Plan / executor / persistence) — foundation
2. A2 (Planner) — depends on C1
3. A5 (post-conditions) — depends on C1
4. A3 (heartbeat) — depends on C1
5. A6 (task-aware observer) — depends on C1
6. Q3 (approval gate) — depends on C1 + A2
7. Q8 (tests) — parallel

## Acceptance

- All items merged. `./gradlew test` green.
- `sprint-13-validation.md` scenarios pass.
- Bot survives the disconnect scenario S13.2 (the critical proof of persistence).
- Plan-driven tasks coexist with single-tool legacy commands (no regression).
