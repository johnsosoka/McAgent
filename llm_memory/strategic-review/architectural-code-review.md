# McAgent Strategic Architectural Review
**Date:** 2026-06-14  
**Reviewer:** Senior Code Reviewer  
**Branch:** feature/issue-10-background-observation-loop  
**Scope:** Autonomous, multi-hour operation readiness

---

## Executive Summary

McAgent has a clean two-module split and a well-reasoned threading model for its current scope. The code is professional, consistently organized, and clearly named. However, the product is being asked to grow from a "responsive assistant" into a "multi-hour autonomous agent," and that transition exposes five structural problems that will cause real failures in production, not merely code smells.

The most urgent issue is that the single `ChatMemory` — a `MessageWindowChatMemory` with a hard cap of 20 messages — is the entire LLM context budget, and it is shared by player turns, framework status dumps, `<player_context>` injections, and urgent observations simultaneously. Over a 3-hour run, the window will cycle through so fast that tool call coherence collapses. This is not a future problem; it is a current problem that simply has not been observed under sustained load yet.

The second urgent issue is the `BotOperations` interface: at 30 methods with domain-crossing responsibilities (Navigation, Combat, Inventory, Building, Observation all in one), it is already a god interface and adding a planner, goal tracker, or combat system will make it unmanageable.

The remaining Critical and Major findings concern zero crash-survival capability, a race condition between the inbound and urgent dispatchers on the shared memory lock, and system-prompt coupling that turns every new tool into a multi-file change.

---

## Findings

### CRITICAL-1: ChatMemory Will Destroy Tool Coherence Under Sustained Load

**File:** `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` (line 47–49)  
**File:** `core/src/main/java/com/mcagent/core/service/LangChain4jService.java`

`MessageWindowChatMemory.withMaxMessages(botProps.getChat().getMaxHistory())` — configured to 20 messages in `application.yml`. Every player command injects a `<player_context>` message and then the actual command, consuming 2 slots per turn. Framework buffer dumps consume additional slots. An active observation scan every second in active mode means the urgent dispatcher pumps `<framework>` messages continuously into the same window. A 3-hour run at even minimal activity will cycle the entire window dozens of times per hour.

The consequence is not gradual degradation — it is hard failure. LangChain4j's `AiServices` proxy presents the `ChatMemory` as the full conversation history for every `assistant.chat(...)` call. When tool call results, goal confirmations, and the bot's own prior decisions rotate out of the window, the model loses awareness of what it was doing. It will restart tasks, contradict itself, or issue conflicting Baritone commands.

Additionally, `processUrgentObservation` calls `addFrameworkContext` first (injecting a `<framework>` message) and then immediately calls `assistant.chat(...)` with a synthetic player prompt duplicating the observation content. This means a single urgent event adds two messages to the window, and both compete with the LLM turn that follows. The message in memory and the message in the `chat()` call are not coordinated — depending on how LangChain4j deduplicates them in the prompt, the model receives either redundant context or contradictory framing.

**Direction:**  
Separate memory into two tiers: a short "working memory" `MessageWindowChatMemory` (8–10 messages, current turn + immediate tool results) and an episodic log stored in the H2 database and periodically summarized. Introduce a `MemoryManager` service that periodically compresses old turns into a rolling summary injected as a system message at the top of the window. This is the standard approach for long-running LLM agents. LangChain4j supports custom `ChatMemory` implementations — a `SummarizingChatMemory` that calls the LLM to summarize every N turns is the correct path.

The `processUrgentObservation` double-injection should be fixed immediately regardless: inject the framework context, then call `assistant.chat("<observation>: react now")` without re-stating the observation text. The model already has it in memory from the `addFrameworkContext` call.

---

### CRITICAL-2: Zero State Survives a Crash or Forced Disconnect

**File:** `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` (lines 152–175)

`shutdownSpringContext()` is called after 60-tick debounce. It tears down everything: `BotEventQueue.shutdown()`, `chatHandler`, `autonomousObserver`, the Spring context itself. No goal, no current task, no plan, no in-progress autonomous state survives this. The H2 database persists `LocationMemoryEntry` and `PlayerNote` but nothing about what the bot was doing.

If the Minecraft client crashes during a 3-hour autonomous mining run — network glitch, GC pause exceeding the debounce window, server-side kick — the bot loses all operational state. On reconnect, `initSpringContext()` starts fresh. The bot has no idea it was mining iron at Y=-32, that it had committed 45 minutes to a goal, or that it was operating under a player-set constraint like "don't go more than 200 blocks from spawn."

This is not a 3-hour problem. This is a problem that will bite during any session longer than the debounce window. Dimension changes and respawns already receive the 60-tick debounce treatment, but Baritone tasks do not survive dimension changes cleanly either.

**Direction:**  
Introduce a `SessionState` JPA entity: persisted `activeGoal`, `activePlan` (serialized JSON), `currentTaskId`, `playerConstraints`, and `sessionStartedAt`. Write on every goal change. On `initSpringContext()`, query for a recent session state (within the last 5 minutes) and restore it. This requires a `GoalRepository` and a small `SessionStateService`. The work is not complex but must happen before multi-hour autonomy is credible.

Additionally, `BotEventQueue.shutdown()` calls `frameworkBuffer.flush()` before shutting down threads — this is correct. However, the inbound queue simply calls `shutdownNow()` (line 170–171), potentially discarding player commands that were in-flight. Consider draining with a short timeout before killing the thread.

---

### CRITICAL-3: Race Condition Between Inbound Dispatcher and Urgent Dispatcher on ChatMemory Lock

**File:** `core/src/main/java/com/mcagent/core/service/LangChain4jService.java` (lines 41–43, 74–86)  
**File:** `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` (lines 153–162)

`processInput` acquires `synchronized (chatMemory)` around the `assistant.chat(...)` call. `processUrgentObservation` also acquires `synchronized (chatMemory)` but does so in two non-atomic steps:

1. `addFrameworkContext(observation)` — acquires and releases the lock to inject the `<framework>` message.
2. Acquires the lock again inside `processUrgentObservation` to call `assistant.chat(...)`.

Between step 1 and step 2, the inbound dispatcher can acquire the lock and call `assistant.chat(...)` with the `<framework>` message already in memory but the synthetic observation prompt not yet sent. The model then processes the framework context with the player's unrelated command, potentially issuing tool calls in a mixed context. When the urgent dispatcher subsequently calls `assistant.chat("Autonomous observation: ...")`, the model sees a phantom "Autonomous observation" prompt after having already responded.

This is a subtle but real race. The `synchronized` block in `processUrgentObservation` should encompass both the `addFrameworkContext` call and the `assistant.chat(...)` call as a single atomic operation. The current code fragment is:

```java
public String processUrgentObservation(String observation) {
    addFrameworkContext(observation);        // releases lock
    synchronized (chatMemory) {             // re-acquires separately
        return assistant.chat("Autonomous observation: " + observation);
    }
}
```

The fix requires either holding the lock across both calls, or restructuring so `addFrameworkContext` takes a flag that allows it to be called under an already-held lock (i.e., make a non-synchronized `addFrameworkContextUnsafe` for internal use).

Additionally, there is no backpressure between the urgent dispatcher and the inbound dispatcher. When active mode fires an urgent observation, it will queue behind or race against any in-flight inbound LLM call. Since both threads block on the LangChain4j HTTP call (30 second timeout), it is entirely possible for the urgent dispatcher to wait 30 seconds to react to a creeper before it can execute. For safety-critical reactions in active mode, the inbound dispatcher must be interruptible or the urgent path must preempt it.

**Direction:**  
Merge the two dispatchers into a single priority-aware `ScheduledExecutorService` where urgent observations get `EventPriority.URGENT` and preempt lower-priority tasks. The current model of two independent thread pools with no coordination is the wrong shape for autonomous safety reactions.

---

### MAJOR-1: BotOperations Is a God Interface — Growth Path Is Broken

**File:** `core/src/main/java/com/mcagent/core/service/BotOperations.java`

`BotOperations` currently has 30 methods spanning Navigation (`navigateTo`, `navigateToXZ`, `navigateToYLevel`, `exploreNear`, `fleeFrom`, `navigateToNearest`, `followPlayer`), Observation (`getPlayerPosition`, `findPlayer`, `getNearbyPlayers`, `getNearbyEntities`, `getNearbyThreats`, `getHealthStatus`), Combat/Safety (`setSafetyMode`, `setPathingBehavior`, `addBlockToAvoid`, `clearAvoidedBlocks`), Inventory (`hasItem`, `countItem`, `getInventorySummary`), Building (`buildPlatform`, `placeBlock`), and Lifecycle (`cancel`, `pause`, `resume`, `setProgressCallback`).

This violates the Interface Segregation Principle directly. Clients that only need inventory queries (`MinecraftTools.checkInventory`) depend on the entire navigation and building surface. Clients that only need observation (`AutonomousObserver`) depend on navigation and building. Every new domain added to the bot will require every implementer to grow.

The deeper problem: for multi-hour autonomous operation you will need at minimum Combat (`attack`, `equipBestWeapon`, `retreatToSafety`), Crafting (`craft`, `hasRecipe`), and Planning (`setGoal`, `getCurrentGoal`, `clearGoal`). Adding these to a single 30-method interface yields an unmanageable monolith. `FabricBaritoneBridge` — already 773 lines — will exceed 1,500 lines.

**Direction:**  
Split into focused role interfaces:
- `NavigationOperations` — all `navigateTo*`, `followPlayer`, `exploreNear`, `fleeFrom`
- `ObservationOperations` — `getPlayerPosition`, `findPlayer`, `getNearbyPlayers`, `getNearbyEntities`, `getNearbyThreats`, `getHealthStatus`, `getCurrentPosition`
- `InventoryOperations` — `hasItem`, `countItem`, `getInventorySummary`
- `BuildingOperations` — `buildPlatform`, `placeBlock`
- `BotControlOperations` — `cancel`, `pause`, `resume`, `setSafetyMode`, `setPathingBehavior`, `addBlockToAvoid`, `clearAvoidedBlocks`, `setProgressCallback`

`FabricBaritoneBridge` implements all of them. Each consumer (`MinecraftTools`, `AutonomousObserver`, a future planner) declares only the interfaces it needs. `BotOperations` can remain as a composite marker interface extending all five for places (like `FabricModConfig`) that need the full implementation.

---

### MAJOR-2: System-Prompt Coupling Forces Two-File Changes Per Tool

**File:** `core/src/main/java/com/mcagent/core/service/Assistant.java`  
**File:** `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`

The `@SystemMessage` on `Assistant.chat()` contains a hand-maintained `<available_tools>` block that enumerates every tool by name with a description. The CLAUDE.md correctly flags "Missing the system-prompt update is the most common reason a tool exists but never gets called." This is a documented ongoing maintenance hazard.

Every tool addition requires: (1) implement on `BotOperations`, (2) implement in `FabricBaritoneBridge`, (3) add `@Tool` method on `MinecraftTools`, (4) add a line to `<available_tools>` in `Assistant.@SystemMessage`. Steps 3 and 4 are redundant — the `@Tool` annotation on `MinecraftTools` already carries a description. LangChain4j's `AiServices` auto-discovers `@Tool` methods and exposes their names and `@Tool("description")` text to the model automatically without an explicit `<available_tools>` catalogue.

The manual catalogue in the system prompt is not wrong — it allows richer descriptions and behavioral hints that `@Tool("...")` annotations cannot capture. But it creates a divergence trap: the annotation description and the system-prompt description can drift independently.

**Direction:**  
Remove the `<available_tools>` catalogue from `@SystemMessage` and rely entirely on LangChain4j's auto-generated tool schema. If richer behavioral guidance is needed for specific tools, add it to the relevant `<*_guidance>` sections in the system prompt, keyed to the tool name. This eliminates the parallel-description problem entirely. As an intermediate step, at minimum add a CI test that verifies every `@Tool`-annotated method in `MinecraftTools` is named in the system prompt catalogue — preventing silent drift.

---

### MAJOR-3: ClientThreadExecutor 5-Second Timeout Is a Deadlock Waiting to Happen Under Load

**File:** `fabric-mod/src/main/java/com/mcagent/fabric/queue/ClientThreadExecutor.java`

`ClientThreadExecutor.execute(Supplier)` submits work to the Minecraft client thread via `mc.execute(...)` and blocks the calling thread (the inbound dispatcher or urgent dispatcher) with a 5-second timeout via `future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)`.

The client thread at 20 TPS has a 50ms tick budget. It runs: game logic, rendering prep, Mixin hooks, Baritone tick callbacks, `baritoneBridge.onClientTick()`, and `autonomousObserver.onTick()`. Under sustained autonomous load — active mode with threat scanning every second, plus an active Baritone build or mine operation — the client thread queue can grow. If a long-running `ClientThreadExecutor` call (e.g., iterating `mc.level.entitiesForRendering()` over a dense entity set) stalls, the 5-second timeout fires and the inbound dispatcher throws `IllegalStateException("Client thread execution failed or timed out")`, which propagates up through `FabricBaritoneBridge`, reaches `MinecraftTools`, and returns an error string to the LLM. The LLM then retries or loops. Meanwhile the client thread is still working.

Worse: `getInventorySummary` iterates the entire inventory, `getNearbyEntities` iterates `entitiesForRendering()` (potentially hundreds of entities), and `getNearbyThreats` does the same. Three of these in sequence from a single LLM turn = three 5-second timeouts possible within one `dispatchLoop` iteration. The dispatcher blocks for up to 15 seconds on a single player command. The inbound queue (capacity 8) fills and starts dropping commands.

**Direction:**  
Move read-only observations (`getNearbyEntities`, `getNearbyThreats`, `getNearbyPlayers`, `getHealthStatus`, `getInventorySummary`) to a periodic cache updated on the client thread during `onClientTick()`. The dispatcher thread reads the cache without blocking the client thread. Only mutating operations (`navigateTo*`, `mine`, `setSafetyMode`, etc.) need the blocking round-trip via `ClientThreadExecutor`. This is the standard pattern in Fabric/Forge mods with background processing threads. Increase the timeout for mutating operations to 10 seconds (Baritone goal setting can genuinely be slow on first call).

---

### MINOR-1: FrameworkMessageBuffer Has No Growth Bound on seenInWindow

**File:** `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java` (line 9)

`seenInWindow` is a `HashSet<String>` that accumulates deduplication keys. It is cleared on delivery (line 112) and on flush. If delivery never fires (consumer throws repeatedly, or the throttle window is never satisfied because messages keep arriving within the 250ms window), `seenInWindow` grows without bound. Over hours, this is a memory leak. The `buffer` Deque is capacity-bounded but `seenInWindow` is not.

**Direction:** Bound `seenInWindow` to `capacity` entries, evicting the oldest key when full (a `LinkedHashSet` with size limit, or checking `seenInWindow.size() >= capacity` before adding).

---

### MINOR-2: BotEventQueue Dedup Scan Is O(n) on Every Enqueue

**File:** `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` (line 96)

`inboundQueue.stream().anyMatch(...)` iterates the queue on every `enqueueCommand` call. The queue is accessed from the Minecraft client thread (via Mixin), while the dispatcher thread drains it. `PriorityBlockingQueue` iteration is weakly consistent, which is acceptable, but the stream copy is proportional to queue size. At capacity 8 this is trivial. If capacity grows, it becomes a hotspot on the client thread. More importantly, the stream + `System.currentTimeMillis()` within the anyMatch predicate means the timestamp check is evaluated lazily during iteration — producing a window-correctness edge case where a message could be deduplicated against a record that has technically expired by the time the comparator evaluates.

**Direction:** Replace the stream scan with a `ConcurrentHashMap<String, Long>` keyed on `playerName + command` to track last-seen timestamps. O(1) lookup, no race.

---

### MINOR-3: BLOCK_MAP in FabricBaritoneBridge Is a Hard-Coded Lookup That Will Not Scale

**File:** `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` (lines 63–75)

`BLOCK_MAP` hardcodes 11 block types. The `resolveBlock` method first checks `BLOCK_MAP`, then falls through to the Minecraft registry. The fallback registry path (`BuiltInRegistries.BLOCK.get(id)`) is strictly superior — it handles every valid Minecraft block ID. The `BLOCK_MAP` only catches legacy uppercase aliases like `"DIAMOND_ORE"`. This dual-path lookup exists because LLM-generated block names can come in varying casing (`DIAMOND_ORE` vs `diamond_ore` vs `minecraft:diamond_ore`).

The correct fix is a single normalization step (`blockType.toLowerCase()`, add `minecraft:` prefix if absent) followed directly by the registry lookup. Remove `BLOCK_MAP` entirely. The 11 entries are a maintenance burden — every new ore type (Copper, Deepslate variants, Nether blocks) requires editing this file.

---

### MINOR-4: SafetyValidator Is Wired But Not Called

**File:** `core/src/main/java/com/mcagent/core/service/SafetyValidator.java`  
**File:** `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`

`SafetyValidator` is a Spring bean registered in `CoreApplication.@Import`. It has a `validate(BotAction action)` method. `MinecraftTools` does not inject or call it. `LangChain4jService` does not call it. The validation is dead code: confirmation for dangerous mining operations is described in `application.yml` (`require-confirmation-for-dangerous: true`) and the system prompt but is never enforced at runtime. A player could say "mine TNT" and the bot would attempt it without challenge.

**Direction:** Either remove `SafetyValidator` and document that safety is delegated to the system prompt, or wire it into `MinecraftTools.mineResource` before delegating to `BotOperations`. Dead code that implies safety guarantees is worse than no code — it creates false trust.

---

### MINOR-5: Testing Gap — BotEventQueue and LangChain4jService Threading Are Untested

**Files:** Tests in `core/src/test/` and `fabric-mod/src/test/`

`LangChain4jServiceTest` has 2 tests. They exercise the happy path and exception path but do not test the double-injection race in `processUrgentObservation`, the memory behavior when `chatMemory.add` is called concurrently, or behavior when `assistant.chat` takes longer than the `responseTimeoutSeconds`.

`BotEventQueue` has zero tests. The inbound queue dedup logic, priority ordering, capacity eviction, and the interaction between the inbound and urgent dispatchers are all unverified. For infrastructure this critical to multi-hour autonomous operation — it is the entire backbone — this is a significant gap.

`FabricBaritoneBridge` is entirely untested (Minecraft dependency makes it hard, but the pure-Java logic like `calculateDirection`, `resolveBlock`, `buildDebounceKey` equivalents can be extracted and tested).

**Direction:** Add a `BotEventQueueTest` with a mock `LangChain4jService`. Test dedup within window, priority ordering, capacity eviction, concurrent urgent + inbound dispatch, and shutdown-while-dispatching. Add a concurrency test to `LangChain4jServiceTest` that fires `processInput` and `processUrgentObservation` from two threads simultaneously and verifies neither produces garbled memory state.

---

### MINOR-6: Shadow JAR Relocation List Is Brittle and Not Validated

**File:** `fabric-mod/build.gradle` (lines 111–134)

The relocation list has 22 entries. There is no automated test or CI check that a newly added transitive dependency is correctly relocated. The typical failure mode: add a dependency, miss the relocation, see a `ClassNotFoundException` only at in-game runtime (not during `./gradlew build`). `com.sun` (line 134) relocates all of `com.sun.*` which is overly broad — it can catch JDK internal packages.

`io.netty` relocation (line 124) shadows Netty entirely, but the Fabric loader ships its own Netty. If the shaded Netty version diverges from Fabric's version, protocol-level incompatibilities can cause silent failures in the LangChain4j HTTP client.

**Direction:** Add a Gradle test that verifies the shadow JAR contains no unshaded third-party packages by scanning for known conflicting package roots (`com.fasterxml`, `org.hibernate`, etc.) in the output JAR. CI should catch relocation misses before in-game testing is required. Remove `com.sun` from the relocation list — it is not a dependency of this project and relocating JDK internals is dangerous.

---

## Recommendations (Priority Order)

### R1 — Introduce Tiered Memory with Summarization (addresses CRITICAL-1)
Implement `SummarizingChatMemory` or equivalent. Split working memory (last 8 messages) from episodic memory (H2-stored summaries injected as a system message). Fix the double-injection in `processUrgentObservation` as an immediate prerequisite. This is the single highest-leverage architectural change for multi-hour operation.

### R2 — Add Session State Persistence (addresses CRITICAL-2)
Design a `SessionState` JPA entity. Write current goal/task on every state change. Restore on `initSpringContext()`. This is what makes the bot resilient to crashes, kicks, and restarts.

### R3 — Fix the Concurrent Memory Race and Dispatcher Priority Model (addresses CRITICAL-3)
Make `processUrgentObservation` atomic. Merge inbound and urgent dispatchers into a single priority-aware queue. Add the concurrency test immediately so the race is caught automatically going forward.

### R4 — Split BotOperations into Role Interfaces (addresses MAJOR-1)
This is the prerequisite for adding a Planner, Combat, or Crafting domain without the entire interface becoming unmanageable. Do it before the next domain is added.

### R5 — Remove the Manual Tool Catalogue from @SystemMessage (addresses MAJOR-2)
Rely on LangChain4j's auto-discovered `@Tool` descriptions. Eliminate the maintenance hazard. Add a CI test to catch drift if a manual catalogue is retained.

### R6 — Cache Read-Only World State to Break Client-Thread Dependency (addresses MAJOR-3)
Move entity scans, health status, and inventory summary to a periodic cache updated on the client tick. Dispatchers read the cache; no blocking occurs.

### R7 — Wire or Remove SafetyValidator (addresses MINOR-4)
Dead safety code is worse than no safety code. Make a decision and enforce it.

### R8 — Add BotEventQueue Unit Tests and LangChain4jService Concurrency Tests (addresses MINOR-5)
Infrastructure this central deserves the same test coverage as `AutonomousObserver`. Add these before merging any multi-hour autonomy features.

### R9 — Replace BLOCK_MAP with Registry Normalization (addresses MINOR-3)
Small, safe, reduces dead code.

### R10 — Bound seenInWindow, Add Shadow JAR Validation (addresses MINOR-1, MINOR-6)
Both are low-risk incremental fixes.

---

## Closing Remarks

The architecture is a solid foundation. The module boundary is real and enforced. The threading model is thoughtful. The event queue, framework buffer, and autonomous observer are well-designed components for their current scope. The code is clean and readable throughout.

The problem is that "responds to chat commands" and "runs autonomously for 3 hours" are different contracts, and the architecture has not yet made that transition. The five Critical and Major findings above — unbounded memory degradation, zero crash survival, a memory race between dispatchers, a god interface approaching its limit, and blocking client-thread calls that compound under tool chaining — are all natural consequences of building the right architecture for Phase 1 without yet designing for Phase 2.

None of these are rewrites. Each has a clear, incremental path. The priority order above is also a delivery sequence: fix memory first (it will break soonest), then persistence, then concurrency, then interface structure.
