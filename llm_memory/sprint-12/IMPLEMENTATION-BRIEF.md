# Sprint 12 — Bot Stays Alive

**Branch:** `feature/sprint-12-survival` (off Sprint 11)
**Goal:** Survival reflexes + missing physical tools. Bot stops dying when left alone. No formal goal abstraction yet.
**Source:** `llm_memory/strategic-review/SYNTHESIS-and-roadmap.md` §4 Sprint 12
**Validation:** `llm_memory/e2e-validation/sprint-12-validation.md`

## Items (in dependency order)

### C2 — Split `BotOperations` into role interfaces

**Why prerequisite:** Sprint 12's survival tools push `BotOperations` past 35 methods. Split first to avoid a giant interface explosion.

**Files (new):**
- `core/src/main/java/com/mcagent/core/service/ops/NavigationOperations.java`
- `core/src/main/java/com/mcagent/core/service/ops/ObservationOperations.java`
- `core/src/main/java/com/mcagent/core/service/ops/InventoryOperations.java`
- `core/src/main/java/com/mcagent/core/service/ops/BuildingOperations.java`
- `core/src/main/java/com/mcagent/core/service/ops/BotControlOperations.java`

**Files (modified):**
- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — becomes a marker interface `extends NavigationOperations, ObservationOperations, ...`. Existing code that consumes `BotOperations` keeps working.
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — narrow constructor to inject only the interfaces it needs (typically: `NavigationOperations`, `InventoryOperations`, `BuildingOperations`, `BotControlOperations`).
- `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java` — narrow to `ObservationOperations`.
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — implements all five interfaces.

**Splits (per CodeReview MAJOR-1):**
- NavigationOperations: `navigateTo*`, `followPlayer`, `exploreNear`, `fleeFrom`, `navigateToNearest`.
- ObservationOperations: `getCurrentPosition`, `getPlayerPosition`, `findPlayer`, `getNearbyPlayers`, `getNearbyEntities`, `getNearbyThreats`, `getHealthStatus`.
- InventoryOperations: `hasItem`, `countItem`, `getInventorySummary`.
- BuildingOperations: `buildPlatform`, `placeBlock`.
- BotControlOperations: `cancel`, `pause`, `resume`, `setSafetyMode`, `setPathingBehavior`, `addBlockToAvoid`, `clearAvoidedBlocks`, `setProgressCallback`.

**Tests:** existing tests must still pass. Add a small test verifying `FabricBaritoneBridge` implements all five and the marker `BotOperations`.

### C3 — Survival/economy tools (subset for Sprint 12)

Sprint 12 ships the **survival-critical** tools. Sprint 13 adds `craft`/`smelt`/chest tools.

**New methods on the relevant ops interfaces:**
- `InventoryOperations`:
  - `String pickupNearbyItems(int radius)` — Baritone has no native; iterate `Minecraft.getInstance().level.getEntities()` for `ItemEntity` within radius, path to each.
- `BotControlOperations`:
  - `boolean eat(String itemId)` — equip food, simulate use action. Returns true if hunger increased. `itemId` may be null → pick best available food.
  - `void placeTorch()` — place a torch at the bot's feet (block below the bot, plus one above if dark). Uses Baritone `Schematic` placement.
- `ObservationOperations`:
  - `int getWorldTime()` — `Minecraft.getInstance().level.getDayTime() % 24000`.
  - `boolean isNight()` — convenience over `getWorldTime`.
  - `int getLightLevel()` — sky light + block light at bot's feet.
- New tools in `MinecraftTools`: `@Tool("...") String eatFood(String itemId)`, `@Tool placeTorchAtFeet()`, `@Tool getWorldTime()`, `@Tool isNight()`, `@Tool getLightLevel()`, `@Tool pickupNearbyItems(int radius)`.

**Tests:** mock `BotOperations` tests for tool methods; bridge-level integration is for in-game testing.

### C4 — Reflex layer (skeleton)

**Why:** Survival decisions sub-100ms; LLM round-trip is seconds.

**File (new):** `core/src/main/java/com/mcagent/core/reflex/SurvivalReflexes.java`. Spring bean. Has method `tickReflexes(WorldSnapshot snapshot)`. `WorldSnapshot` is a small record holding `HealthStatus`, `int hungerLevel`, `int currentLightLevel`, `boolean isNight`, `List<ThreatInfo> threats`.

**Rules (priority-ordered):**
1. HP < 30% → call `bot.setSafetyMode(true)`; if `threats` non-empty, call `bot.fleeFrom(closest.location, 16)`.
2. Hunger < 8 AND has food in inventory → call `bot.eat(null)`.
3. `isNight() && currentLightLevel < 7 && hasItem("minecraft:torch", 1)` → call `bot.placeTorch()`.

**Integration:** Called from `AutonomousObserver.scan()` at the top, BEFORE existing threat/opportunity reporting. Reflex calls are silent (no chat). Each reflex action increments a counter in the Sprint 11 telemetry stack (e.g. `reflex.eat`, `reflex.flee`, `reflex.torch`).

**Mode gating:** Reflexes only fire in `active` mode (Sprint 14 will rename to `auto`). In `passive`/`watch`, only alerts fire.

**Tests:** `SurvivalReflexesTest` — test each rule's preconditions with a fake `BotOperations`.

### C5 — Death/respawn handler

**Files:**
- `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java` — add tick check for `player.isDeadOrDying()` (or use a Mixin on `LocalPlayer#die` if cleaner). When death detected:
  - Record death location to a new `JobTracker.recordDeath(location, currentJob)` method (Sprint 11's JobTracker).
  - Publish framework message: `"You died at (X,Y,Z). Current task suspended."`
  - Cancel Baritone.
- On respawn: detect transition from `dead` → `alive`, publish framework message: `"Respawned at (X,Y,Z). Task X is suspended."` Do NOT auto-resume (player decides).
- Add `getLastDeath()` to `JobTracker` so `agent status` can include `Last death: ...`.

**Tests:** unit test for `JobTracker.recordDeath`; in-game tests cover the Mixin behavior.

### Q7 — Read-only state cache

**File (new):** `fabric-mod/src/main/java/com/mcagent/fabric/cache/WorldStateCache.java`.

A small bean updated on `onClientTick()`. Caches:
- `List<EntityInfo> nearbyEntities` (within max scan radius, typically 32).
- `List<ThreatInfo> threats`.
- `HealthStatus health`.
- `String inventorySummary`.
- `Location currentPosition`.

Update cadence: every 5 ticks (250ms) to keep client-thread cost negligible. Stale-read: dispatcher threads call `cache.get*()` from any thread — these methods read volatile snapshots, no blocking.

**Wiring:**
- `FabricBaritoneBridge` — for read methods (`getNearbyEntities`, `getNearbyThreats`, `getCurrentPosition`, `getHealthStatus`, `getInventorySummary`) FIRST consult the cache. Only fall back to live `ClientThreadExecutor` if cache is empty (early bootstrap).
- `AutonomousObserver` — reads from cache rather than calling bridge methods that round-trip the client thread.

**Counter:** `cache.hit` / `cache.miss` via Sprint 11 telemetry.

**Tests:** `WorldStateCacheTest` with a fake `Minecraft.getInstance()` is hard; isolate the update logic into a pure method `WorldStateCacheUpdater.snapshot(level, player)` that takes inputs and returns a snapshot record — test that.

## Sequencing

1. C2 (interface split) — must land first; other items use the new interfaces.
2. C3 (tools) — parallel with C5 (death) since different files.
3. C4 (reflex) — depends on C3 (`bot.eat`, `bot.placeTorch`).
4. Q7 (cache) — independent; can land in parallel after C2.

## Acceptance

- All Sprint 12 items merged.
- `./gradlew test` green.
- `sprint-12-validation.md` scenarios pass.
- Bot survives the overnight test (S12.9).
- No regression on Sprint 11 e2e scenarios.
