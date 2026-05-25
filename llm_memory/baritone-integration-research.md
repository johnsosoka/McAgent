# Baritone Integration Research Report

**Date:** 2026-05-24  
**Project:** McAgent (Fabric 26.1.2 + Spring Boot / LangChain4j)  
**Reference:** Baritone API v1.10.x - v1.15.0+ (Mojmap)  
**Scope:** Identify ALL Baritone behaviors, goals, settings, and APIs not yet integrated into `FabricBaritoneBridge` or exposed to the LLM via `MinecraftTools`.

---

## 1. Current Integration Inventory

### Implemented in `FabricBaritoneBridge.java`

| Capability | Baritone API Used | Notes |
|------------|-------------------|-------|
| `navigateTo(x,y,z)` | `ICustomGoalProcess.setGoalAndPath(new GoalBlock(...))` | Only `GoalBlock` is used. |
| `followPlayer(name)` | `IFollowProcess.follow(Predicate<Entity>)` | Manual name-filter predicate. |
| `mine(type, max, radius)` | `IMineProcess.mine(max, BlockOptionalMetaLookup)` | Basic ore/block mining. |
| `cancel()` | `cancelEverything()` + `mine.cancel()` + `follow.cancel()` | Hard cancel. |
| `pause()` / `resume()` | Stub — logs "not supported" | `IPathingBehavior.pause()` / `resume()` exist in API reference but are unimplemented. |
| `getCurrentPosition()` | `playerContext.playerFeet()` | Simple coordinate read. |
| Event Listeners | `PathEvent` + `TickEvent` | AT_GOAL, CALC_FAILED, CANCELED, NEXT_CALC_FAILED, tick-based state transitions. |

### Exposed to LLM (`MinecraftTools.java`)

| Tool | Mapped To |
|------|-----------|
| `followPlayer` | `bot.followPlayer` |
| `navigateTo` | `bot.navigateTo(x,y,z)` |
| `navigateToLocation` | `locationMemory` → `bot.navigateTo(x,y,z)` |
| `mineResource` | `bot.mine` |
| `rememberLocation` | `locationMemory.save` + vector index |
| `findLocation` | `vectorMemory.search` + SQL search + distance calc |
| `rememberNote` / `recallNotes` | `playerNoteService` |
| `cancelCurrentOperation` | `bot.cancel` |
| `getCurrentPosition` | `bot.getCurrentPosition` |
| `sendMessage` | `chatService.send` |

### Completely Missing Baritone APIs

- `IBuilderBehavior` — schematics, block placement, construction
- `IInventoryBehavior` — automatic tool selection, inventory awareness
- `ICommandManager` — Baritone command string execution (`#goto`, `#mine`, `#build`)
- `GoalXZ`, `GoalYLevel`, `GoalNear`, `GoalComposite`, `GoalInverted`, `GoalGetToBlock` — alternative pathing goals
- Dynamic `Settings` reconfiguration — safety toggles, mob avoidance, pathing limits
- `PlayerContext` world queries — entity scanning, block scanning beyond mining targets

---

## 2. Gap Analysis by Capability Area

### 2.1 Player Location & Scanning

**The User's Question:** *How can the bot locate a player by name, find their coordinates, or report distance/direction to them?*

**Current State:**
- `followPlayer(name)` creates a `Predicate<Entity>` filter against `entity.getName().getString()`. This only works if the player entity is **already loaded** in the client world and within render/simulation distance.
- There is **no standalone "scan for player" or "get player coordinates" API** in Baritone. Baritone's `IFollowBehavior` does not expose a player search or coordinate reporting function.

**What Baritone Offers:**
- `IFollowProcess.follow(String playerName)` — according to the API reference, Baritone can accept a raw player name string (not just a `Predicate<Entity>`). This may do its own entity resolution internally. **Worth testing.**
- `IFollowProcess.getFollowingEntity()` — returns the `Entity` currently being followed.
- `IFollowProcess.getDistanceToTarget()` — returns distance to followed entity.

**What Requires Minecraft Client APIs:**
To locate a player *without* following them, the bridge must use:
```java
Minecraft mc = Minecraft.getInstance();
mc.level.entities().forEach(entity -> {
    if (entity instanceof Player && entity.getName().getString().equals(playerName)) {
        BlockPos pos = entity.blockPosition();
        double distance = entity.distanceTo(mc.player);
        // Report direction via vector math
    }
});
```
This is **fully feasible** in a Fabric client mod. The `ClientLevel` entity list is accessible on the client thread.

**Assessment:**
- **Feasible:** Yes — Fabric client mod has full access to `Minecraft.getInstance().level` and entity lists.
- **BotOperations Change:** Add `findPlayer(String name)` returning `Location` or null, and `getPlayerInfo(String name)` returning coordinates + distance + direction.
- **MinecraftTools Change:** Add `@Tool` methods: `locatePlayer`, `getNearbyPlayers`, `reportDistanceToPlayer`.
- **Safety:** Safe — read-only operation, no movement triggered.

---

### 2.2 Building / Placement

**Baritone API:** `IBuilderBehavior`

```java
IBuilderBehavior builder = baritone.getBuilderBehavior();
builder.build(ISchematic schematic, BlockPos origin);
```

Supported schematics:
- `FillSchematic(width, height, length, Block)` — fills a rectangular prism with a single block type.
- `CompositeSchematic` — layered/multi-part builds.
- `SchematicSystem.INSTANCE.load(path)` — loads `.schematic`, `.litematic`, or `.nbt` files.

**What the Bot Could Do:**
- Build a simple wall/floor/platform by specifying two corners and a block type.
- Place a specific block at a coordinate.
- Load and build a schematic file from a known path.
- **Material verification** — check inventory for required blocks before starting.

**Assessment:**
- **Feasible:** Yes, but with caveats.
- **BotOperations Change:** Add `buildArea(BlockPos c1, BlockPos c2, String blockType)`, `placeBlock(int x, int y, int z, String blockType)`, `buildSchematic(String path, int x, int y, int z)`.
- **MinecraftTools Change:** Add `@Tool` methods: `buildPlatform`, `buildWall`, `placeBlock`, `buildSchematic`.
- **Blockers:** Baritone builder requires blocks to be in the player inventory. In survival mode, material exhaustion will halt the build. Need to expose inventory checks. The `IBuilderBehavior` API is powerful but can be brittle with modded blocks or complex redstone structures.
- **Safety:** Moderate — block placement modifies the world. Should verify `settings.allowPlace` is enabled and materials are available.

---

### 2.3 Inventory Management

**Baritone API:** `InventoryBehavior` (implicit, mostly settings-driven)

Relevant settings:
- `settings.mineToolRequirement` — require correct tool for mining
- `settings.mineToolSilkTouch` — prefer silk touch
- Automatic tool selection is handled internally by Baritone when breaking blocks.

**What is NOT Exposed:**
- Reading inventory contents (needs Minecraft `Player.getInventory()`)
- Counting items of a specific type
- Checking if the bot has a required tool
- Dropping items / clearing inventory

**Assessment:**
- **Feasible:** Yes — Minecraft inventory is fully accessible client-side.
- **BotOperations Change:** Add `hasItem(String itemId, int count)`, `countItem(String itemId)`, `selectTool(String blockType)`.
- **MinecraftTools Change:** Add `@Tool` methods: `checkInventory`, `doIHaveToolFor`, `countItems`.
- **Safety:** Safe — read-only unless we add drop/throw functions. Valuable for pre-flight checks ("Do I have enough cobblestone to build this wall?").

---

### 2.4 Combat / Safety

**Baritone API:** Settings-driven avoidance + `CombatBehavior`

Relevant settings:
- `settings.avoidance` — enable mob/danger avoidance
- `settings.mobAvoidanceRadius` — distance to avoid mobs (default 8.0)
- `settings.mobAvoidanceCoefficient` — path cost multiplier near mobs (default 1.5)
- `settings.lavaSafetyDistance` — blocks to stay away from lava
- `settings.maxFallHeightNoWater` — max fall without water (default 3)
- `settings.maxFallHeightBucket` — max fall with water bucket (default 256)

**What is NOT Exposed:**
- Dynamic safety mode toggling (e.g., "be extra careful" vs "go fast")
- Health monitoring / death detection
- Emergency retreat / fleeing behavior

**How to Implement Fleeing:**
Using `GoalInverted` or simply setting a `GoalBlock` in the opposite direction of a threat:
```java
// Flee from a mob or lava
GoalInverted flee = new GoalInverted(new GoalBlock(threatPos));
pathing.setGoal(flee);
pathing.pathToGoal();
```
Or use `GoalNear` with a safe zone center.

**Assessment:**
- **Feasible:** Yes.
- **BotOperations Change:** Add `setSafetyMode(SafetyMode mode)`, `fleeFrom(int x, int y, int z, int distance)`, `getHealthStatus()`.
- **MinecraftTools Change:** Add `@Tool` methods: `enableSafeMode`, `retreatTo`, `checkHealth`.
- **Safety:** High value — prevents bot death. Should be paired with health monitoring via `Minecraft.getInstance().player.getHealth()`.

---

### 2.5 Advanced Pathing Goals

**Currently Used:** `GoalBlock` only (via `ICustomGoalProcess`).

**Available but Unused:**

| Goal Class | Description | LLM Use Case |
|------------|-------------|--------------|
| `GoalXZ` | Specific X,Z (any Y) | "Go to coordinates (100, 200) on the surface" — useful when Y is unknown/irrelevant. |
| `GoalYLevel` | Specific Y level (any X,Z) | "Go to Y=11 for diamond mining" — strip mining depth. |
| `GoalNear` | Within radius of coordinate | "Explore within 50 blocks of spawn" — area-bound navigation. |
| `GoalComposite` | Any of multiple goals | "Go to the nearest of these 3 waypoints" — multi-destination routing. |
| `GoalInverted` | Away from coordinate | "Run away from that creeper" — fleeing / retreat. |
| `GoalGetToBlock` | Touching specific block | "Get close enough to open this chest" — interaction range. |
| `GoalTwoBlocks` | Two-block space | "Walk through this doorway" — precise 2-high space navigation. |
| `GoalAxis` | On specific X OR Z axis | "Walk along the X=100 line" — corridor/grid alignment. |

**Assessment:**
- **Feasible:** All goals are pure Baritone API, zero external dependencies.
- **BotOperations Change:** Extend `navigateTo` overloads or add `navigateToXZ`, `navigateToYLevel`, `navigateToNearest(List<Location>)`, `exploreNear(Location, int radius)`, `fleeFrom(Location, int distance)`.
- **MinecraftTools Change:** Add `@Tool` methods: `goToSurfaceCoordinates`, `goToDepth`, `exploreArea`, `fleeFrom`, `goToNearestOf`.
- **Safety:** Safe — these are navigation intents only. No world modification.

---

### 2.6 World Scanning & Awareness

**Baritone's Native Scanning:**
- `IMineProcess` scans for ore/block locations internally (`getOreLocationsCount()`, `getCurrentTarget()`).
- There is **no general-purpose "scan for any block type within radius without mining it"** API in Baritone.

**Minecraft Client World Scanning:**
The Fabric client mod can scan the loaded chunk cache directly:
```java
ClientLevel level = Minecraft.getInstance().level;
BlockPos playerPos = ...;
Block targetBlock = Blocks.DIAMOND_ORE;

// Scan loaded chunks within radius
for (int dx = -radius; dx <= radius; dx++) {
    for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
            BlockPos check = playerPos.offset(dx, dy, dz);
            if (level.getBlockState(check).is(targetBlock)) {
                // Found one
            }
        }
    }
}
```

**Entity Scanning:**
Same approach — iterate `level.entities()` to find animals, mobs, items on ground, or other players.

**What the LLM Could Ask:**
- "Are there any creepers nearby?"
- "Find the nearest water source."
- "Scan for oak logs in a 30-block radius."
- "Where is the closest village structure?" (Note: structure finding requires seed data or exploration map; not trivial from client alone.)

**Assessment:**
- **Feasible:** Yes for blocks and entities in loaded chunks. Structures require server data or external APIs.
- **BotOperations Change:** Add `scanForBlock(String blockType, int radius)`, `scanForEntity(String entityType, int radius)`, `getNearbyThreats(int radius)`.
- **MinecraftTools Change:** Add `@Tool` methods: `scanForBlocks`, `scanForMobs`, `scanForPlayers`, `whatIsAroundMe`.
- **Performance Concern:** Scanning large radii (e.g., 128 blocks) is O(n³) and will lag the client. Should cap radius (e.g., 32 blocks) or use chunk-iteration instead of block-iteration.
- **Safety:** Read-only. Safe.

---

### 2.7 CommandManager Fallback

**Baritone API:** `ICommandManager`

```java
ICommandManager commands = baritone.getCommandManager();
commands.execute("goto 100 64 200");
commands.execute("mine 64 diamond_ore");
commands.execute("build my_house.schematic");
commands.execute("stop");
```

**Use Cases:**
- Fallback for commands not directly wrapped in the Java bridge.
- Rapid prototyping — add a new capability without writing a Java wrapper.
- Executing Baritone's built-in `#commands` that have complex internal logic (e.g., `#build` with material verification, `#follow` with offset settings).

**Assessment:**
- **Feasible:** Yes.
- **BotOperations Change:** Add `executeBaritoneCommand(String command)`.
- **MinecraftTools Change:** Add `@Tool` method: `executeRawBaritoneCommand` (with safety warning in description).
- **Safety:** Moderate — raw commands bypass our validation layer. Could accidentally enable dangerous settings. Should be used as a developer/escape-hatch tool, not a primary user-facing tool. Consider a `safeMode` flag that rejects raw commands.

---

## 3. Fabric 26.1.2 Feasibility Summary

| Capability | Feasible | Effort | Notes |
|------------|----------|--------|-------|
| Player scanning / locate by name | ✅ Yes | Low | Use `Minecraft.getInstance().level.entities()` |
| Advanced pathing goals | ✅ Yes | Low | Pure Baritone API — just import goal classes |
| Pause / Resume pathing | ✅ Yes | Low | `IPathingBehavior.pause()` / `resume()` likely available in v1.15+; test it |
| Building / schematics | ✅ Yes | Medium | Requires `IBuilderBehavior`, `FillSchematic`, inventory checks |
| Inventory queries | ✅ Yes | Low | Minecraft `Player.getInventory()` client-side |
| Safety mode / mob avoidance settings | ✅ Yes | Low | Settings toggles only |
| Fleeing / retreat (`GoalInverted`) | ✅ Yes | Low | New goal type + small math for safe direction |
| World block scanning | ✅ Yes | Medium | Chunk iteration; watch for lag with large radii |
| Entity scanning (mobs, animals) | ✅ Yes | Low | `ClientLevel.entities()` iteration |
| CommandManager fallback | ✅ Yes | Low | One-line wrapper around `commands.execute()` |
| Health / death monitoring | ✅ Yes | Low | `player.getHealth()`, `player.isAlive()` |
| Structure finding | ⚠️ Partial | High | Client-side only knows loaded chunks; server seed needed for true structure location |

---

## 4. Recommended Interface Changes

### `BotOperations.java` additions

```java
// Player & Entity Awareness
Location findPlayer(String playerName); // null if not found
List<PlayerInfo> getNearbyPlayers(int radius);
List<EntityInfo> getNearbyEntities(String entityType, int radius); // "creeper", "pig", etc.

// Advanced Navigation
PathResult navigateToXZ(int x, int z);
PathResult navigateToYLevel(int y);
PathResult navigateToNearest(List<Location> candidates);
PathResult exploreNear(Location center, int radius); // GoalNear
PathResult fleeFrom(Location threat, int safeDistance); // GoalInverted

// Building
PathResult buildPlatform(int x1, int y1, int z1, int x2, int y2, int z2, String blockType);
PathResult placeBlock(int x, int y, int z, String blockType);
PathResult buildSchematic(String schematicPath, int x, int y, int z);

// Inventory
boolean hasItem(String itemId, int count);
int countItem(String itemId);
String getInventorySummary();

// Safety & Status
void setSafetyMode(boolean enabled); // toggles avoidance, parkour, sprint
HealthStatus getHealthStatus();
List<ThreatInfo> getNearbyThreats(int radius);

// World Scanning
List<BlockScanResult> scanForBlock(String blockType, int radius);

// Fallback
PathResult executeBaritoneCommand(String command);
```

### `MinecraftTools.java` additions

```java
@Tool("Locate a player by name and report their coordinates, distance, and direction")
public String locatePlayer(@P("Player name") String playerName) { ... }

@Tool("List nearby players within a radius")
public String scanForPlayers(@P("Search radius in blocks") int radius) { ... }

@Tool("List nearby mobs or animals of a given type")
public String scanForEntities(@P("Entity type, e.g. CREEPER, PIG, COW, ZOMBIE") String entityType,
                              @P("Search radius") int radius) { ... }

@Tool("Navigate to surface X,Z coordinates (any Y level)")
public String navigateToSurface(@P("X coordinate") int x, @P("Z coordinate") int z) { ... }

@Tool("Go to a specific Y level, useful for strip mining")
public String goToDepth(@P("Target Y level") int y) { ... }

@Tool("Flee from specific coordinates to maintain a safe distance")
public String fleeFrom(@P("Threat X") int x, @P("Threat Y") int y, @P("Threat Z") int z,
                       @P("Safe distance in blocks") int distance) { ... }

@Tool("Build a filled rectangular area with a specific block")
public String buildArea(@P("Corner 1 X") int x1, ...) { ... }

@Tool("Place a single block at coordinates")
public String placeBlockAt(@P("X") int x, @P("Y") int y, @P("Z") int z,
                           @P("Block type") String blockType) { ... }

@Tool("Check if the bot has an item in inventory")
public String checkInventory(@P("Item ID, e.g. minecraft:cobblestone") String itemId,
                             @P("Minimum count needed") int count) { ... }

@Tool("Enable or disable safe mode (mob avoidance, no parkour, conservative movement)")
public String setSafetyMode(@P("true to enable safe mode, false for normal") boolean enabled) { ... }

@Tool("Report current health and any nearby threats")
public String getStatusReport() { ... }

@Tool("Scan for a specific block type in the nearby loaded chunks")
public String scanForBlocks(@P("Block type, e.g. DIAMOND_ORE, WATER") String blockType,
                            @P("Search radius, max 64") int radius) { ... }

@Tool("Execute a raw Baritone command as a fallback. Use sparingly.")
public String executeRawCommand(@P("Baritone command without # prefix, e.g. 'goto 100 64 200'") String command) { ... }
```

---

## 5. Blockers & Safety Concerns

### Performance
- **World scanning** (block-by-block iteration) in a large radius is O(n³). Cap at radius 32 for blocks, 64 for entities. Use chunk-level iteration where possible.
- **Builder behavior** can cause significant frame drops when calculating placement order for large schematics. Start with small `FillSchematic` builds.

### Thread Safety
- All Baritone and Minecraft client calls must execute on the **main client thread**. The bridge already runs inside Fabric's tick loop. If the Spring Boot core calls these from async threads, they must be dispatched via `Minecraft.getInstance().execute(...)`.

### Safety Settings
- `allowPlace` must be `true` for building. In a shared multiplayer world, accidental placement can grief structures. Consider requiring explicit user confirmation for build commands in `MinecraftTools` (or at least logging loudly).
- `allowBreak` is already enabled. Building adds a new world-modification vector.
- **Death loops:** If the bot dies and respawns, it could path back to its death point into danger. The bridge should listen for death events and enter a defensive safety mode.

### Baritone Version Compatibility
- The project claims compatibility with "Baritone v1.15.0+ API (Mojmap for MC 26.1.2)". The reference document covers v1.10.x (1.20.4). Some class names may have shifted between 1.10 and 1.15. Verify that `IBuilderBehavior`, `ICommandManager`, and goal classes still exist with the same names in the Mojmapped JAR.
- `pause()` / `resume()` are marked "not supported" in the bridge. The reference shows them on `IPathingBehavior`. They may actually be available in the newer version — test before assuming they are missing.

---

## 6. Top 5 Highest-Value Integrations to Implement Next

### 1. Player / Entity Scanning (`locatePlayer`, `scanForEntities`)
**Why:** Directly answers the user's question. Enables the LLM to report situational awareness ("Player Steve is 45 blocks north-east"), find mobs, locate animals, and make intelligent decisions without blindly pathing. It is read-only, safe, and requires only Minecraft client API access — no Baritone changes.

### 2. Advanced Pathing Goals (`GoalXZ`, `GoalYLevel`, `GoalNear`, `GoalInverted`)
**Why:** Dramatically expands navigation vocabulary. The LLM can say "go to Y=11" for mining, "explore near spawn," or "run away from the creeper." These are pure Baritone APIs, low effort to wire through `BotOperations`, and immediately useful for survival gameplay.

### 3. Safety Mode & Health Monitoring (`setSafetyMode`, `getStatusReport`, `fleeFrom`)
**Why:** Prevents bot death and inventory loss. Survival bots die without mob avoidance and health awareness. Toggling `avoidance`, `allowParkour`, and `mobAvoidanceRadius` dynamically lets the LLM choose risk level per task. Adding `fleeFrom` with `GoalInverted` gives an emergency exit strategy.

### 4. Inventory Queries (`checkInventory`, `hasItem`, `countItem`)
**Why:** Unlocks intelligent task planning. Before mining, the LLM can check if tools are available. Before building, it can verify materials. Before following a player into the Nether, it can confirm food supply. Read-only and trivial to implement via `Player.getInventory()`.

### 5. Building / Placement (`buildPlatform`, `placeBlock`, `buildSchematic`)
**Why:** Moves the bot from "gatherer" to "builder." Even basic `FillSchematic` support lets the LLM construct shelters, bridges, or farms. This is medium effort because it requires `IBuilderBehavior`, material checks, and safety guards, but it is the largest capability gap. Start with `placeBlock` and `buildPlatform` (FillSchematic) before tackling external schematic files.

---

*Report generated for McAgent technical team. Recommend delegating implementation of these integrations to the senior-python-engineer / junior-engineer agents once interface contracts are scaffolded.*
