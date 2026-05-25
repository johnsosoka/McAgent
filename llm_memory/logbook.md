# McAgent Development Logbook

**Format:** Each entry is a dated session summary with what changed, why, and relevant issues/branches.

---

## 2026-05-24 — Session: Expand LLM Capabilities Planning

**Branch:** `feature/expand-llm-capabilities`
**Issues:** #3, #4, #5, #6, #7, #8

### What we did

1. **Branch preparation** — Created `feature/expand-llm-capabilities` from latest `origin/main`.
2. **Baritone integration research** — Dispatched research agent to audit all Baritone APIs against current implementation. Identified 6 major capability gaps:
   - Player / entity scanning (#4)
   - Advanced pathing goals (#5)
   - Safety mode & health monitoring (#6)
   - Inventory queries (#7)
   - Building / placement (#8)
   - Message queuing & harness architecture (#3)
3. **Message queuing analysis** — Audited existing harness threading. Found no formal queue, no deduplication, no priority, no backpressure, and a thread-safety gap where Spring calls Baritone off the main client thread.
4. **GitHub issues created** — Filed 6 enhancement issues in `johnsosoka/McAgent` with full acceptance criteria and interface proposals.

### Files changed / created

- `llm_memory/baritone-integration-research.md` — New. Comprehensive gap analysis.
- `llm_memory/message-queuing-analysis.md` — New. Queue architecture gap analysis + proposed event bus design.
- `llm_memory/logbook.md` — New. This file.

### Decisions made

- **#3 (Message Queuing) is the foundation.** It must be built before or alongside any new Baritone integration to prevent event spam and fix thread safety.
- **Recommended order:** #3 → #4 → #5 → #7 → #6 → #8.
- Each issue will get its own focused branch fetched from the GitHub backlog.

---

---

## 2026-05-24 — Session: Implement Issue #3 — Message Queuing & Event Harness Architecture

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)

### What we did

1. **Created new queue infrastructure in `fabric-mod`**:
   - `ClientThreadExecutor` — dispatches Baritone / Minecraft calls back to the main client thread using `CompletableFuture` + `Minecraft.execute()`. Fixes the thread-safety gap where Spring's background thread was calling Baritone directly.
   - `FrameworkMessageBuffer` — ring buffer (capacity 32) with deduplication, throttling (250ms), and batching for Baritone/game status messages. Prevents spam from flooding `ChatMemory`.
   - `BotEventQueue` — central coordinator with a `PriorityBlockingQueue` for inbound player commands (capacity 8, dedup window 500ms) and a `LinkedBlockingQueue` for outbound chat responses (capacity 4). A dedicated dispatcher thread pulls inbound events and calls `LangChain4jService.processInput()`.
   - `EventPriority` / `InboundEvent` — immutable records for priority-based command scheduling (CANCEL > EMERGENCY > NORMAL).

2. **Updated existing fabric-mod classes**:
   - `FabricChatHandler` — replaced direct `ExecutorService` with `BotEventQueue.enqueueCommand()`. Cancel/stop/halt commands are automatically promoted to `EventPriority.CANCEL`.
   - `FabricBaritoneBridge` — **all** `BotOperations` methods (`navigateTo`, `followPlayer`, `mine`, `cancel`, `pause`, `resume`, `getCurrentPosition`) now wrap their Baritone / Minecraft calls inside `ClientThreadExecutor.execute()`.
   - `McAgentFabricMod` — wires `FrameworkMessageBuffer` → `langChainService.addFrameworkContext()`, `BotEventQueue` → `chatHandler` + outbound drainer, `ChatService` sender → `botEventQueue::enqueueOutbound`.

3. **Verified build & tests**:
   - `:fabric-mod:compileJava` — SUCCESS
   - `:core:compileJava` — SUCCESS
   - `:core:test` — 20 tests PASSED

### Files changed / created

- `fabric-mod/src/main/java/com/mcagent/fabric/queue/ClientThreadExecutor.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/EventPriority.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/InboundEvent.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — Modified (removed executor, wired queue).
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — Modified (client-thread dispatch for all operations).
- `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` — Modified (queue lifecycle wiring).

### Decisions made

- Kept `BotOperations` interface **unchanged** (synchronous). Thread safety is enforced at the implementation layer via `ClientThreadExecutor`, avoiding cascading changes across `core/` tests and `MinecraftTools`.
- `FabricChatSender` was **not structurally modified** — it remains the final rate-limited, thread-safe transport. The outbound queue in `BotEventQueue` drains into it, satisfying "wired to outbound queue" without duplicating its existing rate-limit logic.
- Memory backpressure (`max-history: 20`) was already present in `application.yml` via `MessageWindowChatMemory`. No config change needed.

### Remaining work on #3

- [x] JAR built and deployed to `~/Library/Application Support/minecraft/mods/`
- [x] Validation guide created at `llm_memory/issue-3-validation-guide.md`
- [ ] Manual test session completed
- [ ] Merge to `main` (pending human approval)

---

---

## 2026-05-24 — Session: Fix .env Loading for Production Launcher

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)

### What we did

**Problem identified from log analysis:**
Bot was unresponsive because the Spring context failed to boot — `FIREWORKS_API_KEY` was not found. The `.env` file existed in the project directory, but the Minecraft launcher runs from `~/Library/Application Support/minecraft/` and never sees it.

**Solution implemented:**
1. Added `java-dotenv` dependency to `core/build.gradle`.
2. Created `EnvLoader` in `core` that tries multiple candidate paths in priority order:
   - Environment variable / system property (already set)
   - Explicit path passed from fabric-mod (e.g. Fabric config directory)
   - Project working directory `./.env` (for dev / Gradle runs)
   - Platform-specific Minecraft config directories:
     - macOS: `~/Library/Application Support/minecraft/config/mc-agent.env`
     - Linux: `~/.minecraft/config/mc-agent.env`
     - Windows: `~/AppData/Roaming/.minecraft/config/mc-agent.env`
3. Wired `EnvLoader.load()` into `McAgentFabricMod.initSpringContext()` before `context.refresh()`, passing `FabricLoader.getInstance().getConfigDir().resolve("mc-agent.env")`.
4. Copied the project's `.env` to `~/Library/Application Support/minecraft/config/mc-agent.env` for production use.

**Files changed / created:**
- `core/build.gradle` — Added `java-dotenv` dependency.
- `core/src/main/java/com/mcagent/core/config/EnvLoader.java` — New. Multi-path .env loader.
- `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` — Modified. Calls `EnvLoader.load()` before Spring context refresh.

**Build & deploy:**
- `:fabric-mod:shadowJar` — SUCCESS
- `:core:test` — 20 tests PASSED
- JAR deployed to `~/Library/Application Support/minecraft/mods/`

---

---

## 2026-05-24 — Session: Fix resolveApiKey to read System Properties

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)

### What we did

**Problem identified from log analysis:**
The `.env` loader was working (`Loaded 19 variables from .env`), but the Spring context still failed with `Fireworks API key not configured`. 

**Root cause:** `LangChain4jConfig.resolveApiKey()` only checked `System.getenv()` (OS environment variables). It did NOT check `System.getProperty()` (Java system properties), which is what `EnvLoader` sets via `System.setProperty()`. When launching from the Minecraft app (not a terminal), there is no OS env var, so the key was invisible.

**Fix:** Updated `resolveApiKey()` to also check `System.getProperty("FIREWORKS_API_KEY")` as a fallback after `System.getenv()`.

**Files changed:**
- `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` — `resolveApiKey()` now checks system properties.

**Build & deploy:**
- `:fabric-mod:shadowJar` — SUCCESS
- JAR redeployed to `~/Library/Application Support/minecraft/mods/`

---

---

## 2026-05-24 — Session: Hardening — NPE Fix, Double Shutdown, Disconnect Debounce

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)

### What we did

**Problem identified from log analysis:**
The Spring context successfully booted and the bot was alive, but the user's command `come to -10,143,-79` had **no trigger word** (`bot`/`agent`/`mcagent`/`hey bot`), so it was ignored. Then the context shut down unexpectedly, and Baritone spammed `Pathing was cancelled` into the void, eventually crashing with NPE because the progress callback tried to publish to a null `BotEventQueue`.

**Three bugs fixed:**

1. **Double shutdown** — `FabricChatHandler.shutdown()` called `botEventQueue.shutdown()`, then `McAgentFabricMod.shutdownSpringContext()` called it again. Removed queue shutdown from `FabricChatHandler`; only the mod lifecycle manages it now.

2. **NPE after shutdown** — The Baritone progress callback lambda `msg -> botEventQueue.publishFramework(msg)` captured `botEventQueue` by reference. When shutdown set it to `null`, Baritone's in-flight events crashed.
   - Fixed by adding a null check inside the lambda.
   - Fixed by clearing the callback to a no-op in `shutdownSpringContext()` **before** nulling `baritoneBridge`.

3. **Aggressive disconnect detection** — `mc.getConnection()` briefly returns `null` during dimension changes, respawns, or server lag. The old code shut down immediately.
   - Added a **disconnect debounce counter** (`DISCONNECT_DEBOUNCE_TICKS = 60`, i.e. 3 seconds at 20 TPS).
   - Only calls `shutdownSpringContext()` if the connection stays null for the full debounce period.

**Files changed:**
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — `shutdown()` no longer shuts down the queue.
- `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` — Null-safe callback, disconnect debounce, proper cleanup order.

**Build & deploy:**
- `:fabric-mod:shadowJar` — SUCCESS
- `:core:test` — 20 tests PASSED
- JAR deployed to `~/Library/Application Support/minecraft/mods/`

---

---

## 2026-05-25 — Session: Player Context Injection & Pronoun Resolution

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3) (continued)

### What we did

**Problem identified from user testing:**
- `agent what is my location?` → bot reported **its own** coordinates (27, 127, -19) instead of the player's
- `agent come here` → bot asked "What's your player name?" because it had no concept of "me"

**Root cause:** The LLM received raw player messages with **zero context** about who sent them. `processInput(String playerMessage, String playerId)` received the player name but never injected it into the conversation.

**Three-part fix:**

1. **Player context injection** — `LangChain4jService.processInput()` now calls `injectPlayerContext(playerId)` before `assistant.chat()`. This adds a `<player_context>` message to `ChatMemory` containing the player's name and their current coordinates (looked up via `BotOperations.getPlayerPosition()`).

2. **`getPlayerPosition()` tool** — New method in `BotOperations` / `FabricBaritoneBridge` / `MinecraftTools` that scans loaded world entities to find a player by name and return their coordinates. This is a preview of issue #4 functionality.

3. **System prompt pronoun resolution** — Updated `Assistant` `@SystemMessage` to explain:
   - `<player_context>` tags identify the current speaker
   - "my" / "me" / "I" = the human player in context
   - "your" / "you" = the bot itself
   - When asked "where am I?", use `getPlayerPosition()` not `getCurrentPosition()`
   - When asked "come here" / "follow me", use `followPlayer()` with the name from context

**Files changed:**
- `core/src/main/java/com/mcagent/core/service/LangChain4jService.java` — Added `injectPlayerContext()`, wired `BotOperations`.
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — Added `<pronoun_resolution>` section to system prompt.
- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — Added `getPlayerPosition(String)`.
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — Added `@Tool getPlayerPosition`.
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — Implemented `getPlayerPosition` via `Minecraft.getInstance().level.entitiesForRendering()` scan.
- `core/src/test/java/com/mcagent/core/TestRunner.java` — Added mock `getPlayerPosition`.
- `core/src/test/java/com/mcagent/core/service/LangChain4jServiceTest.java` — Added `ChatMemory` and `BotOperations` mocks.

**Build & deploy:**
- `:core:test` — 20 tests PASSED
- `:fabric-mod:shadowJar` — SUCCESS
- JAR deployed to `~/Library/Application Support/minecraft/mods/`

---

---

## 2026-05-25 — Session: Fix Timing — Late LLM "Starting" Messages

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3) (continued)

### What we did

**Problem identified from user testing:**
After saying `agent come here`, the bot physically arrived at the player's location quickly, but ~20 seconds later the LLM sent a chat message saying "I've started navigating to your coordinates..." — which was confusing because the bot was already there.

**Root cause:** LLM API round-trip (~20-30s) is slower than short-distance pathing. The `navigateTo()` tool executes immediately during the `chat()` call, but the LLM's conversational response isn't returned until the API completes. By then, the bot has already arrived.

**Two-part fix:**

1. **Immediate chat feedback from tools** — `navigateTo()` and `followPlayer()` in `MinecraftTools` now call `chatService.send()` immediately upon success. The player sees "Navigating to (X, Y, Z)" or "Following player X" in real-time, independent of LLM latency.

2. **System prompt timing guidance** — Added `<timing_guidance>` to `Assistant` system prompt: navigation and follow tools send their own immediate confirmation. The LLM should NOT send a separate "I'm starting to..." message because it may arrive after the bot has already arrived.

**Files changed:**
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — `navigateTo()` and `followPlayer()` now send immediate chat.
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — Added `<timing_guidance>` section.

**Build & deploy:**
- `:core:test` — 20 tests PASSED
- `:fabric-mod:shadowJar` — SUCCESS
- JAR deployed to `~/Library/Application Support/minecraft/mods/`

---

---

## 2026-05-25 — Session: Final Validation & Acceptance

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3) (concluded)

### What we did

**Test session results — all green:**
- `agent come here` → immediate "Following player lexicon_social" in chat, bot followed correctly
- `agent this is my house, can you save that?` → processed and saved location
- `agent stop following me` → priority=CANCEL, stopped following, saved location
- Clean disconnect when player left — no NPE, no crash
- Clean shutdown at session end

**Log analysis:**
- No errors, exceptions, NPEs, or `ConcurrentModificationException`
- Spring context: Beans: 45, all initialized successfully
- Queue: `BotEventQueue started. Inbound capacity=8, outbound capacity=4`
- Commands processed with correct priorities (NORMAL for chat, CANCEL for stop)
- Framework messages: throttled and deduplicated as designed

**Remaining observation (non-blocking):**
- Baritone emits `Pathing was cancelled` in idle state before pathing begins. This is normal Baritone behavior, not a bug. The framework buffer handles it gracefully.

### Status

- [x] Message queuing infrastructure
- [x] Framework throttling & deduplication
- [x] Thread safety via `ClientThreadExecutor`
- [x] `.env` loading from Minecraft config directory
- [x] Player context injection with pronoun resolution
- [x] Immediate tool chat feedback (no LLM latency)
- [x] Disconnect debounce (3s)
- [x] NPE hardening after shutdown
- [x] All 20 core tests passing
- [x] Shadow JAR builds successfully
- [x] In-game validation passed

**Ready for merge.**

---

## 2026-05-25 — Session: Sprint Planning — Issue #4 Player / Entity Scanning

**Branch:** `issue/4-player-entity-scanning`
**Issue:** [#4](https://github.com/johnsosoka/McAgent/issues/4)
**Status:** Sprint started, branch cut from `origin/main`

### What we did

1. **Repo audit** — Verified `main` is clean and up-to-date with `origin/main`. PR #9 (Issue #3) fully merged.
2. **Issue triage** — Reviewed all 5 open enhancement issues (#4–#8). Confirmed #4 as the logical next sprint based on:
   - The planned roadmap (#3 → #4 → #5 → #7 → #6 → #8)
   - Foundation dependency for #5 (fleeing needs threat location) and #6 (safety needs nearby mobs)
   - Low risk (read-only, no movement or inventory changes)
   - Partially previewed via `getPlayerPosition()` during #3
3. **Branch created** — `issue/4-player-entity-scanning` cut from latest `main`.

### Goals for this sprint

- `locatePlayer(String playerName)` — coordinates, distance, direction for a specific player
- `scanForPlayers(int radius)` — list all loaded players within radius
- `scanForEntities(String entityType, int radius)` — filter by type (CREEPER, PIG, ZOMBIE, etc.)
- Read-only world inspection — zero movement triggered by these tools
- Integration tests in `core/` and `fabric-mod/`

### Interface targets

**BotOperations.java:**
- `Location findPlayer(String playerName)`
- `List<PlayerInfo> getNearbyPlayers(int radius)`
- `List<EntityInfo> getNearbyEntities(String entityType, int radius)`

**MinecraftTools.java:**
- `@Tool locatePlayer`
- `@Tool scanForPlayers`
- `@Tool scanForEntities`

---

## 2026-05-25 — Session: Implement Issue #4 — Player / Entity Scanning

**Branch:** `issue/4-player-entity-scanning`
**Issue:** [#4](https://github.com/johnsosoka/McAgent/issues/4)
**Status:** Implementation complete, code review passed, build green

### What we did

1. **Scaffolded core interfaces and DTOs:**
   - `PlayerInfo.java` — immutable builder DTO: name, location, distance, direction
   - `EntityInfo.java` — immutable builder DTO: type, location, distance, direction
   - `BotOperations.java` — added `findPlayer()`, `getNearbyPlayers()`, `getNearbyEntities()`

2. **Delegated implementation to senior engineer agent** — full brief at `llm_memory/issue-4-implementation-brief.md`.

3. **Implemented in `FabricBaritoneBridge`:**
   - All 3 methods wrapped in `ClientThreadExecutor.execute()` for thread safety
   - `findPlayer()` — scans loaded entities, matches `Player` by name, computes distance + direction
   - `getNearbyPlayers()` — collects players within radius, excludes bot itself, sorts by distance
   - `getNearbyEntities()` — filters by `entity.getClass().getSimpleName()`, sorts by distance
   - `calculateDirection()` — 8-sector compass (N, NE, E, SE, S, SW, W, NW) using Minecraft yaw convention

4. **Added `@Tool` methods to `MinecraftTools`:**
   - `locatePlayer(playerName)` — "Player X is at (Y), Z blocks D"
   - `scanForPlayers(radius)` — one line per player
   - `scanForEntities(entityType, radius)` — one line per mob/animal

5. **Updated `Assistant.java` system prompt:**
   - Added 3 new tools to `<available_tools>`
   - Added `<entity_scanning_guidance>` explaining read-only semantics

6. **Updated test layer:**
   - `TestRunner.MockBotOperations` — implements new methods with mock data
   - `MinecraftToolsTest.java` — 6 unit tests (found / not-found for each tool) using Mockito

7. **Code review findings & fixes:**
   - **Critical:** Fixed inverted direction calculation (`atan2(-dx, dz)` → `atan2(dx, -dz)`) to match Minecraft coordinate system
   - **Style:** Removed redundant diamond-type arguments (`new ArrayList<>()`)
   - **Mock:** Parameterized `getNearbyEntities` mock to return the requested entity type

### Files changed / created

- `core/src/main/java/com/mcagent/core/model/PlayerInfo.java` — New.
- `core/src/main/java/com/mcagent/core/model/EntityInfo.java` — New.
- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — Added 3 methods.
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — Implemented 3 methods + `calculateDirection()`.
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — Added 3 `@Tool` methods.
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — Updated system prompt.
- `core/src/test/java/com/mcagent/core/TestRunner.java` — Mock implementations.
- `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java` — New. 6 tests.
- `llm_memory/issue-4-implementation-brief.md` — New. Agent brief.

### Build & test results

- `:core:compileJava` — SUCCESS
- `:core:test` — **26 tests PASSED** (20 existing + 6 new)
- `:fabric-mod:compileJava` — SUCCESS
- `:fabric-mod:shadowJar` — SUCCESS, deployed to `~/Library/Application Support/minecraft/mods/`

### Remaining work

- [x] Implement entity scanning in `FabricBaritoneBridge`
- [x] Add `@Tool` methods to `MinecraftTools`
- [x] Add system prompt guidance for entity scanning tools
- [x] Core unit tests
- [x] Fabric integration validation (in-game test) — PASSED
- [x] Merge to `main` — PR #11 merged, Issue #4 closed

### Validation guide

- `llm_memory/issue-4-validation-guide.md` — New. Step-by-step in-game test plan with 10 commands.

### Related ticket filed

- **Issue #10** — [Background observation loop](https://github.com/johnsosoka/McAgent/issues/10): Architectural planning ticket for autonomous threat detection and proactive agent behavior. Filed during sprint to capture the idea while #4 scanning primitives are fresh. Will be actionable after #5–#8 build out more capabilities.

---

---

## 2026-05-25 — Session: Sprint Planning — Issue #5 Advanced Pathing Goals

**Branch:** `issue/5-advanced-pathing-goals`
**Issue:** [#5](https://github.com/johnsosoka/McAgent/issues/5)
**Status:** Sprint started, branch cut from latest `main`

### What we did

1. **Repo audit** — Verified `main` is clean and up-to-date with `origin/main`. PR #11 (Issue #4) fully merged.
2. **Issue triage** — Reviewed all 5 open enhancement issues (#5–#8, #10). Confirmed #5 as the logical next sprint based on:
   - The planned roadmap (#3 → #4 → **#5** → #7 → #6 → #8)
   - #6 (safety/fleeing) is blocked until `GoalInverted` is built in #5
   - Pure Baritone API — clean wiring of goal classes, no new Minecraft client APIs needed
   - Low risk (movement commands only, no block placement or inventory changes)
3. **Branch created** — `issue/5-advanced-pathing-goals` cut from latest `main`.

### Goals for this sprint

- `navigateToXZ(int x, int z)` — path to surface X,Z coordinates (any Y level)
- `navigateToYLevel(int y)` — path to a specific depth (strip mining)
- `exploreNear(Location center, int radius)` — explore within radius of a point
- `fleeFrom(Location threat, int safeDistance)` — retreat using `GoalInverted`
- `navigateToNearest(List<Location> candidates)` — path to nearest of multiple waypoints (optional stretch)

### Interface targets

**BotOperations.java:**
- `PathResult navigateToXZ(int x, int z)`
- `PathResult navigateToYLevel(int y)`
- `PathResult exploreNear(Location center, int radius)`
- `PathResult fleeFrom(Location threat, int safeDistance)`
- `PathResult navigateToNearest(List<Location> candidates)` (stretch)

**MinecraftTools.java:**
- `@Tool navigateToSurface(x, z)` — "Navigate to surface X,Z coordinates"
- `@Tool goToDepth(y)` — "Go to a specific Y level, useful for strip mining"
- `@Tool exploreArea(x, y, z, radius)` — "Explore within a radius of a center point"
- `@Tool fleeFrom(x, y, z, distance)` — "Flee from specific coordinates to maintain safe distance"

### Remaining work

- [ ] Add methods to `BotOperations` interface
- [ ] Implement in `FabricBaritoneBridge` via `ClientThreadExecutor`
- [ ] Add `@Tool` methods to `MinecraftTools`
- [ ] Update `Assistant` system prompt
- [ ] Update `TestRunner` mock
- [ ] Unit tests
- [ ] Fabric integration validation (in-game test)
- [ ] Merge to `main` (pending human approval)

---

---

## 2026-05-25 — Session: Implement Issue #5 — Advanced Pathing Goals

**Branch:** `issue/5-advanced-pathing-goals`
**Issue:** [#5](https://github.com/johnsosoka/McAgent/issues/5)
**Status:** Implementation complete, code review passed, build green

### What we did

1. **Scaffolded `BotOperations` interface** — Added 5 new methods:
   - `navigateToXZ(int x, int z)` — surface X,Z travel
   - `navigateToYLevel(int y)` — vertical depth navigation
   - `exploreNear(Location center, int radius)` — exploration within radius
   - `fleeFrom(Location threat, int safeDistance)` — emergency retreat
   - `navigateToNearest(List<Location> candidates)` — nearest waypoint selection

2. **Delegated implementation to senior engineer agent** — Full brief at `llm_memory/issue-5-implementation-brief.md`.

3. **Implemented in `FabricBaritoneBridge`:**
   - All 5 methods wrapped in `ClientThreadExecutor.execute()` for thread safety
   - `navigateToXZ` — `GoalXZ`
   - `navigateToYLevel` — `GoalYLevel`
   - `exploreNear` — `GoalNear`
   - `fleeFrom` — computes retreat point away from threat, paths to it with `GoalBlock`
   - `navigateToNearest` — `GoalComposite` built from `GoalBlock` instances

4. **Added `@Tool` methods to `MinecraftTools`:**
   - `navigateToSurface(x, z)` — surface travel
   - `goToDepth(y)` — strip mining depth
   - `exploreArea(x, y, z, radius)` — exploration
   - `fleeFrom(x, y, z, distance)` — retreat
   - `navigateToNearestLocation(locationNames)` — parses comma-separated names, looks up in `locationMemory`, picks nearest

5. **Updated `Assistant.java` system prompt:**
   - Added 5 new tools to `<available_tools>`
   - Added `<advanced_pathing_guidance>` section

6. **Updated test layer:**
   - `TestRunner.MockBotOperations` — implements new methods with mock data
   - `MinecraftToolsTest.java` — 13 unit tests (success + failure for each tool)

7. **Code review findings & fixes:**
   - **Critical:** Fixed `fleeFrom` — removed incorrect `GoalInverted` wrapper (would path toward threat instead of away); now paths directly to computed retreat point
   - **Safety:** Fixed retreat Y coordinate to use bot's current Y instead of threat's Y
   - **Tests:** Added failure-path tests for `goToDepth`, `exploreArea`, `fleeFrom`
   - **Repo hygiene:** Updated `.gitignore` to exclude build artifacts, IDE files, OS files, runtime logs

### Files changed / created

- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — Added 5 methods.
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — Implemented 5 methods.
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — Added 5 `@Tool` methods.
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — Updated system prompt.
- `core/src/test/java/com/mcagent/core/TestRunner.java` — Mock implementations.
- `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java` — 13 tests (6 existing + 7 new).
- `llm_memory/issue-5-implementation-brief.md` — New. Agent brief.
- `.gitignore` — Updated with standard exclusions.

### Build & test results

- `:core:compileJava` — SUCCESS
- `:core:test` — **33 tests PASSED** (20 existing + 13 new)
- `:fabric-mod:compileJava` — SUCCESS

### Remaining work

- [x] Add methods to `BotOperations` interface
- [x] Implement in `FabricBaritoneBridge` via `ClientThreadExecutor`
- [x] Add `@Tool` methods to `MinecraftTools`
- [x] Update `Assistant` system prompt
- [x] Update `TestRunner` mock
- [x] Unit tests
- [ ] Fabric integration validation (in-game test)
- [ ] Merge to `main` (pending human approval)

---

---

## 2026-05-25 — Session: Sprint Planning — Issue #6 Safety Mode & Health Monitoring

**Branch:** `issue/6-safety-mode-health-monitoring`
**Issue:** [#6](https://github.com/johnsosoka/McAgent/issues/6)
**Status:** Sprint started, branch cut from latest `main`

### What we did

1. **Repo audit** — Verified `main` is clean and up-to-date with `origin/main`. Issue #5 implementation complete on branch.
2. **Issue triage** — Reviewed open backlog. Issue #6 is the logical next sprint:
   - Both blockers resolved: #4 (entity scanning for threats) and #5 (`fleeFrom` for retreat)
   - Scope expanded during planning to include **respectful pathing / house mode**
   - High value: prevents property damage, enables door usage, configurable block avoidance
3. **Updated Issue #6** — Added `setPathingBehavior`, `avoidBreakingBlock`, `clearBlockAvoidance` to acceptance criteria.
4. **Branch created** — `issue/6-safety-mode-health-monitoring` cut from latest `main`.

### Goals for this sprint

- `setSafetyMode(boolean enabled)` — toggles mob avoidance, parkour, sprint, block breaking, door usage
- `getHealthStatus()` — reports health, hunger, basic status
- `getNearbyThreats(int radius)` — lists hostile mobs with distance/direction
- `setPathingBehavior(String mode)` — "careful", "aggressive", or "default"
- `avoidBreakingBlock(String blockType)` — add block to avoid-breaking list
- `clearBlockAvoidance()` — reset avoidance rules

### Interface targets

**BotOperations.java:**
- `void setSafetyMode(boolean enabled)`
- `HealthStatus getHealthStatus()`
- `List<ThreatInfo> getNearbyThreats(int radius)`
- `void setPathingBehavior(String mode)`
- `void addBlockToAvoid(String blockType)`
- `void clearAvoidedBlocks()`

**MinecraftTools.java:**
- `@Tool setSafetyMode(boolean)` — "Enable/disable safe mode"
- `@Tool getStatusReport()` — "Report health and threats"
- `@Tool setPathingBehavior(String)` — "Set careful/aggressive/default pathing"
- `@Tool avoidBreakingBlock(String)` — "Add block to avoid-breaking list"
- `@Tool clearBlockAvoidance()` — "Clear custom avoidance rules"

### Behavior Modes

| Mode | allowBreak | allowParkour | allowSprint | allowOpenDoors | blocksToAvoidBreaking |
|------|-----------|-------------|-------------|---------------|----------------------|
| **careful** | false | false | false | true | common building blocks |
| **aggressive** | true | true | true | false | empty |
| **default** | true | true | true | true | empty |

---

---

## 2026-05-25 — Session: Implement Issue #6 — Safety Mode & Health Monitoring

**Branch:** `issue/6-safety-mode-health-monitoring`
**Issue:** [#6](https://github.com/johnsosoka/McAgent/issues/6)
**Status:** Implementation complete, build green

### What we did

1. **Scaffolded `BotOperations` interface** — Added 6 new methods and 2 records:
   - `setSafetyMode(boolean)` — toggles mob avoidance, parkour, sprint, block breaking
   - `getHealthStatus()` — returns `HealthStatus` record with health, maxHealth, foodLevel, armorLevel
   - `getNearbyThreats(radius)` — scans for 16 hostile mob types, returns `List<ThreatInfo>`
   - `setPathingBehavior(mode)` — "careful", "aggressive", or "default"
   - `addBlockToAvoid(blockType)` — adds block to Baritone's avoid-breaking list
   - `clearAvoidedBlocks()` — clears avoidance rules

2. **Delegated implementation to senior engineer agent** — Full brief at `llm_memory/issue-6-implementation-brief.md`.

3. **Implemented in `FabricBaritoneBridge`:**
   - All methods wrapped in `ClientThreadExecutor.execute()`
   - `setSafetyMode` — toggles `allowBreak`, `allowParkour`, `allowSprint`, `avoidance`, `mobAvoidanceRadius`, and populates/clears `blocksToAvoidBreaking`
   - `getHealthStatus` — reads health, max health, food level, armor value from `Minecraft.getInstance().player`
   - `getNearbyThreats` — scans entities, filters 16 hostile types (Creeper, Zombie, Skeleton, Spider, Enderman, Witch, Drowned, Husk, Stray, WitherSkeleton, Blaze, Ghast, PiglinBrute, Vindicator, Evoker, Ravager)
   - `setPathingBehavior` — careful mode avoids breaking + parkour + sprint, populates common avoid blocks; aggressive mode allows everything; default restores Baritone defaults
   - `addBlockToAvoid` — resolves block ID via existing `resolveBlock()` helper, adds to `blocksToAvoidBreaking`
   - `clearAvoidedBlocks` — clears the avoid list

4. **Added `@Tool` methods to `MinecraftTools`:**
   - `setSafetyMode(boolean)` — toggles safe mode with chat confirmation
   - `getStatusReport()` — reports health + nearby threats (radius 32)
   - `setPathingBehavior(mode)` — validates and sets careful/aggressive/default
   - `avoidBreakingBlock(blockType)` — adds block to avoid list
   - `clearBlockAvoidance()` — clears all avoidance rules

5. **Updated `Assistant.java` system prompt:**
   - Added 5 new tools to `<available_tools>`
   - Added `<safety_guidance>` section explaining safe mode, careful pathing, and block avoidance

6. **Updated test layer:**
   - `TestRunner.MockBotOperations` — implements all 6 new methods
   - `MinecraftToolsTest.java` — 12 unit tests covering all new tools

7. **Baritone API notes:**
   - `allowOpenDoors` setting does not exist in Baritone 1.17.0 — omitted from implementation
   - Settings accessed via `BaritoneAPI.getSettings()` (global settings, not per-baritone)
   - `mobAvoidanceRadius` is `Integer` type, not `Double`

### Files changed / created

- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — Added 6 methods + 2 records.
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — Implemented 6 methods + helpers.
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — Added 5 `@Tool` methods.
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — Updated system prompt.
- `core/src/test/java/com/mcagent/core/TestRunner.java` — Mock implementations.
- `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java` — 12 new tests.
- `llm_memory/issue-6-implementation-brief.md` — New. Agent brief.

### Build & test results

- `:core:compileJava` — SUCCESS
- `:core:test` — **32 tests PASSED**
- `:fabric-mod:compileJava` — SUCCESS

### Remaining work

- [x] Add methods to `BotOperations` interface
- [x] Implement in `FabricBaritoneBridge` via `ClientThreadExecutor`
- [x] Add `@Tool` methods to `MinecraftTools`
- [x] Update `Assistant` system prompt
- [x] Update `TestRunner` mock
- [x] Unit tests
- [ ] Fabric integration validation (in-game test)
- [ ] Merge to `main` (pending human approval)

---

*Next in queue after #6: Issue #7 — Inventory Queries (`checkInventory`, `getInventorySummary`).*
