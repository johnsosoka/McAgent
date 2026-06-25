# Sprint 14 — Autonomy & Routines

**Branch:** `feature/sprint-14-autonomy` (off Sprint 13)
**Goal:** `agent auto` works. Bot picks goals from a Routine library when idle. Failures become recoverable.
**Source:** `llm_memory/strategic-review/SYNTHESIS-and-roadmap.md` §4 Sprint 14
**Validation:** `llm_memory/e2e-validation/sprint-14-validation.md`

## Items

### A1 — Three-mode model

**Files:**
- `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java` — replace ad-hoc mode flags with a `BotMode { STANDBY, WATCH, AUTO }` enum.
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — bypasses for `agent standby`, `agent watch`, `agent auto`. Legacy `passive mode`, `active mode`, `start watching`, etc. remain as aliases with a deprecation chat warning.
- Status command output (Sprint 11 F3): include current mode.
- Reflex layer (Sprint 12 C4): activates in WATCH (alerts only — flee/eat for safety) and AUTO (full reflexes). STANDBY = nothing.

**Mode semantics (per UX review R1):**
- `STANDBY`: observer off, no autonomous action.
- `WATCH`: observer on; reflexes only for safety (flee, eat at critical); observations sit in memory until player chats.
- `AUTO`: observer on; reflexes full; bot can push new goals when idle (via Routine library).

### A4 — Named Routines

**New package: `core/src/main/java/com/mcagent/core/routine/`**

```java
@Entity
@Table(name = "routine_definition")
public class RoutineDefinition {
    @Id @GeneratedValue private UUID id;
    @Column(unique=true) private String name;
    private String description;
    @Column(columnDefinition="TEXT") private String planTemplate;  // JSON with ${param} placeholders
    private String parametersJson;
    private boolean systemSeeded;  // vs LLM-defined
}

public interface RoutineService {
    UUID define(String name, String description, String template, Map<String, String> params);
    Plan invoke(String name, Map<String, Object> args);
    Optional<RoutineDefinition> findByName(String name);
}
```

**Hand-seeded routines** (created in `RoutineSeeder` @PostConstruct):
- `returnHome` — Navigate to base waypoint.
- `mineUntilFull` — MineStep until inventory ≥ 90% full or `timeout` elapsed.
- `depositAndContinue` — Navigate to nearest chest, deposit, return.
- `nightShelter` — Navigate to base, sleep at bed.
- `eatIfHungry` — Single EatStep if hunger < 10 (idempotent).

**New tools:**
- `@Tool submitRoutine(name, paramsJson)` — invoke a routine.
- `@Tool defineRoutine(name, description, planTemplateJson, paramsJson)` — LLM defines a routine.
- `@Tool listRoutines()` — list known.

**Idle behavior in AUTO mode:**
When no current task: `AutonomousObserver` (or new `AutoModeDriver` bean) selects a routine based on world state:
- Hungry → `eatIfHungry`.
- Inventory full → `depositAndContinue` (if a chest waypoint exists).
- Night → `nightShelter`.
- Otherwise → `mineUntilFull` of cobblestone (placeholder; configurable).

### C4 — Formal Behavior Tree

Refactor Sprint 12's `SurvivalReflexes` rules into a proper BT structure. ~150 LOC.

**New files:**
- `core/src/main/java/com/mcagent/core/reflex/bt/BtNode.java` — `enum Status { SUCCESS, FAILURE, RUNNING }`, `Status tick(BtContext ctx)`.
- `Sequence`, `Selector`, `Condition`, `Action` impls.

**Tree (priority root = Selector):**
```
Selector (root, evaluates children left-to-right; returns first non-FAILURE)
├── Sequence "critical safety"
│   ├── Condition: HP < 30%
│   ├── Action: safetyModeOn
│   └── Action: fleeFromNearestThreat
├── Sequence "feed self"
│   ├── Condition: hunger < 8
│   ├── Condition: hasFoodInventory
│   └── Action: eat
├── Sequence "light up dark area"
│   ├── Condition: isNight
│   ├── Condition: lightLevel < 7
│   ├── Condition: hasTorch
│   └── Action: placeTorch
└── Action: idle  (always succeeds; lets AUTO driver pick a goal)
```

`AutonomousObserver` ticks the BT each scan in WATCH (only safety branch active) and AUTO (full tree).

### Q1 — Explain-then-replan on failure

Per DEPS / SYNTHESIS Q1.

**File:** `core/src/main/java/com/mcagent/core/service/LangChain4jService.java`.

Add:
```java
public PlanRevision processFailureEscalation(TaskFailure failure) {
    String prompt = "Plan step failed. " + failure.toStructuredPrompt() +
                    "\n\nFirst, explain in 1-2 sentences why this failed. " +
                    "Then choose: RETRY, REVISE, or ABANDON. If REVISE, output the new plan as JSON.";
    // single LLM turn; parse response into PlanRevision { explanation, action, optional revisedPlan }
}
```

`TaskExecutor` on step failure calls `processFailureEscalation`. RETRY = restart current step (capped by `RetryPolicy`). REVISE = submit the revised plan. ABANDON = clear current task, chat the explanation to the player.

### Q9 — Disambiguation guidance

**File:** `core/src/main/java/com/mcagent/core/service/Assistant.java`.

Add to `@SystemMessage`:
```
<disambiguation_guidance>
Before starting a multi-step task, assess ambiguity:
- HIGH confidence (clear location, materials, scope): submit plan with approval gate.
- MEDIUM confidence (location unclear OR materials unspecified): ask 1 clarifying question.
- LOW confidence (intent unclear OR conflicting constraints): ask up to 2 questions, do not start.
Never ask more than 2 clarifying questions. If still unclear, propose the most conservative interpretation and ask for approval.
</disambiguation_guidance>
```

### P6 — Per-role model split

**Files:**
- `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` — add second `ChatModel` bean for planner (smaller/faster model — e.g. `accounts/fireworks/models/llama-v3p1-8b-instruct`). Configure with `llm.planner.model` in `application.yml`.
- `Planner` AI-service constructed with the planner model.
- `Assistant` continues to use the Kimi-K2 main model.
- Telemetry counters split: `llm.executor.calls`, `llm.planner.calls`, `llm.critic.calls` (for Q1 explain step).

### P9 — Session cost cap

**Files:**
- `core/src/main/java/com/mcagent/core/config/LLMProperties.java` — add `sessionCostCapUsd` (nullable; null = no cap).
- New service `core/src/main/java/com/mcagent/core/telemetry/CostTracker.java`. Listens to LangChain4j response → counts tokens → multiplies by per-model pricing (config). Persisted per session.
- `LangChain4jConfig` wraps the `ChatModel` with a `CostCapChatModel` decorator that throws `CostCapExceededException` once the cap is reached.
- `agent reset cost` bypass clears the counter.
- `agent stats` includes `cost: $0.XX / cap $0.YY`.

## Sequencing

1. A1 (three-mode) — first; everything else uses the mode.
2. A4 (routines) — depends on Sprint 13's Plan + executor.
3. C4 (formal BT) — refactor of Sprint 12's reflex skeleton.
4. Q1 (explain-replan) — depends on TaskFailure structure.
5. Q9 (disambiguation) — prompt-only.
6. P6 (per-role models) — independent.
7. P9 (cost cap) — independent.

## Acceptance

- `sprint-14-validation.md` scenarios pass.
- 3-hour autonomy run (S14.9) succeeds.
- No regressions on Sprint 11/12/13 tests.
