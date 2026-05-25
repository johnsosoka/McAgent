# Issue #5 Implementation Brief — Advanced Pathing Goals

**Branch:** `issue/5-advanced-pathing-goals`
**Agent:** senior-engineer (Java)

---

## Already Scaffolded (do NOT modify)

- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — 5 new methods added:
  - `PathResult navigateToXZ(int x, int z)`
  - `PathResult navigateToYLevel(int y)`
  - `PathResult exploreNear(Location center, int radius)`
  - `PathResult fleeFrom(Location threat, int safeDistance)`
  - `PathResult navigateToNearest(List<Location> candidates)`

---

## Your Tasks

### 1. Implement `FabricBaritoneBridge.java`

Add implementations for the 5 new `BotOperations` methods. Use Minecraft client APIs inside `ClientThreadExecutor.execute()` (follow the existing pattern for thread safety).

**Baritone Goal Classes to use:**

| Method | Baritone Goal Class | Import Path |
|--------|---------------------|-------------|
| `navigateToXZ(x, z)` | `GoalXZ` | `baritone.api.pathing.goals.GoalXZ` |
| `navigateToYLevel(y)` | `GoalYLevel` | `baritone.api.pathing.goals.GoalYLevel` |
| `exploreNear(center, radius)` | `GoalNear` | `baritone.api.pathing.goals.GoalNear` |
| `fleeFrom(threat, safeDistance)` | `GoalInverted` | `baritone.api.pathing.goals.GoalInverted` |
| `navigateToNearest(candidates)` | `GoalComposite` | `baritone.api.pathing.goals.GoalComposite` |

**Reference implementation already exists:** `navigateTo(int x, int y, int z)` at lines 95-105 shows exactly how to set a goal and path using `baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(...))`.

**Key requirements:**
- All methods must run inside `ClientThreadExecutor.execute()`
- `navigateToXZ(x, z)`: set `GoalXZ` with the target X,Z coordinates
- `navigateToYLevel(y)`: set `GoalYLevel` with the target Y level
- `exploreNear(center, radius)`: use `GoalNear(new BlockPos(center.x(), center.y(), center.z()), radius)` OR pick a random point within the radius and use `GoalXZ` — `GoalNear` is preferred if available in this Baritone version
- `fleeFrom(threat, safeDistance)`: compute a retreat point opposite the threat from the bot's current position, then set `GoalInverted(new GoalBlock(retreatPos))` OR simply `GoalInverted(new GoalBlock(new BlockPos(threat.x(), threat.y(), threat.z())))` — the latter tells Baritone to path AWAY from the threat coordinate. If `GoalInverted` works with a distance, use it; otherwise compute a retreat point manually:
  ```java
  Location bot = getCurrentPosition();
  double dx = bot.x() - threat.x();
  double dz = bot.z() - threat.z();
  double dist = Math.sqrt(dx*dx + dz*dz);
  double scale = safeDistance / Math.max(dist, 1.0);
  int retreatX = (int) (bot.x() + dx * scale);
  int retreatZ = (int) (bot.z() + dz * scale);
  // set GoalInverted or GoalBlock at retreat point
  ```
- `navigateToNearest(candidates)`: build a `GoalComposite` from `GoalBlock` instances for each candidate location, then set and path. If `GoalComposite` is not available, pick the nearest candidate by Euclidean distance and navigate to it directly.
- All methods return `PathResult` with `.success(true/false)`, `.message(...)`, `.type(PathResultType.SUCCESS/ERROR)`
- Call `notify("...")` for progress feedback, matching existing pattern

### 2. Add `@Tool` methods to `MinecraftTools.java`

Add 5 new tool methods following the exact style of existing tools (e.g. `navigateTo`). Each should:
- Log at `info` level
- Call the appropriate `bot` method
- Return a concise, human-readable string suitable for chat
- Send immediate chat feedback via `chatService.send()` on success (matching the timing guidance pattern from #3)

**Tool descriptions:**

```java
@Tool("Navigate to surface X,Z coordinates. The bot will find any Y level to reach the target.")
public String navigateToSurface(@P("X coordinate") int x, @P("Z coordinate") int z) { ... }
```
- On success: chat "Navigating to surface coordinates (X, Z)"
- Return: "Navigating to surface (X, Z)" or "Cannot navigate: ..."

```java
@Tool("Go to a specific Y level. Useful for strip mining at a particular depth.")
public String goToDepth(@P("Target Y level") int y) { ... }
```
- On success: chat "Going to Y=<y>"
- Return: "Going to Y=<y>" or "Cannot navigate: ..."

```java
@Tool("Explore within a radius of a center point. The bot will path to a random point inside the area.")
public String exploreArea(@P("Center X") int x, @P("Center Y") int y, @P("Center Z") int z,
                          @P("Radius in blocks") int radius) { ... }
```
- On success: chat "Exploring within <radius> blocks of (X, Y, Z)"
- Return: "Exploring within <radius> blocks of (X, Y, Z)" or "Cannot explore: ..."

```java
@Tool("Flee from specific coordinates to maintain a safe distance. Use when threats are nearby.")
public String fleeFrom(@P("Threat X") int x, @P("Threat Y") int y, @P("Threat Z") int z,
                       @P("Safe distance in blocks") int distance) { ... }
```
- On success: chat "Fleeing to maintain <distance> blocks from threat"
- Return: "Fleeing from (X, Y, Z), maintaining <distance> blocks" or "Cannot flee: ..."

```java
@Tool("Navigate to the nearest of multiple remembered locations by name. Provide a comma-separated list of location names.")
public String navigateToNearestLocation(@P("Comma-separated location names") String locationNames) { ... }
```
- Parse comma-separated names, look up each in `locationMemory`, build `List<Location>` of candidates
- Call `bot.navigateToNearest(candidates)`
- On success: chat "Navigating to nearest of <names>"
- Return: "Navigating to nearest of <names>" or "Cannot navigate: ..."

### 3. Update `Assistant.java` system prompt

Add the 5 new tools to the `<available_tools>` section. Add a brief `<advanced_pathing_guidance>` section explaining:
- `navigateToSurface` is for X,Z surface travel (any Y)
- `goToDepth` is for vertical navigation (strip mining)
- `exploreArea` is for exploration within a radius
- `fleeFrom` is for emergency retreat from threats
- `navigateToNearestLocation` picks the closest of multiple waypoints

### 4. Update `TestRunner.java` mock

The inner class `MockBotOperations` must implement the 5 new interface methods. Return sensible mock data:
- `navigateToXZ(x, z)` — `PathResult.success("Mock pathing to surface (" + x + ", " + z + ")")`
- `navigateToYLevel(y)` — `PathResult.success("Mock going to Y=" + y)`
- `exploreNear(center, radius)` — `PathResult.success("Mock exploring near " + center)`
- `fleeFrom(threat, distance)` — `PathResult.success("Mock fleeing from " + threat)`
- `navigateToNearest(candidates)` — `PathResult.success("Mock navigating to nearest of " + candidates.size() + " locations")`

### 5. Add unit tests

In a new `MinecraftToolsTest.java` or the existing one, add tests verifying:
- `navigateToSurface` calls `bot.navigateToXZ` and formats output
- `goToDepth` calls `bot.navigateToYLevel` and formats output
- `exploreArea` calls `bot.exploreNear` and formats output
- `fleeFrom` calls `bot.fleeFrom` and formats output
- `navigateToNearestLocation` parses names, calls `bot.navigateToNearest`

Use Mockito. Keep tests simple and intention-revealing.

---

## Coding Standards (CRITICAL)

1. **Follow existing code style exactly.** Match indentation, naming, Lombok usage, and Spring patterns.
2. **All fabric-mod code must use `ClientThreadExecutor.execute()`** — never call Baritone APIs from a background thread.
3. **Use `dev.langchain4j.agent.tool.P` for parameter descriptions.**
4. **Keep methods small and single-purpose.** Extract helper methods for goal construction, string formatting, etc.
5. **Do NOT modify existing tools or interface methods** — only ADD new ones.
6. **Do NOT change the return type of `Assistant.chat()`**.
7. **Build must pass:** after your changes, `./gradlew :core:compileJava` and `./gradlew :core:test` must succeed.

---

## Verification Checklist

- [ ] `BotOperations` interface compiles (5 new methods present)
- [ ] `FabricBaritoneBridge` implements all 5 methods with `ClientThreadExecutor`
- [ ] `MinecraftTools` has 5 new `@Tool` methods with proper `@P` annotations
- [ ] `Assistant` system prompt lists the new tools
- [ ] `TestRunner.MockBotOperations` implements the new methods
- [ ] Core tests pass (`./gradlew :core:test`)
- [ ] Core compiles (`./gradlew :core:compileJava`)
- [ ] Fabric-mod compiles (`./gradlew :fabric-mod:compileJava`)

---

## Files to Modify

1. `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java`
2. `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`
3. `core/src/main/java/com/mcagent/core/service/Assistant.java`
4. `core/src/test/java/com/mcagent/core/TestRunner.java`
5. `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java` (or new test file)

---

## Reference: Baritone Goal Class Usage

```java
// GoalXZ
baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));

// GoalYLevel
baritone.getCustomGoalProcess().setGoalAndPath(new GoalYLevel(y));

// GoalNear
baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(new BlockPos(x, y, z), radius));

// GoalInverted
baritone.getCustomGoalProcess().setGoalAndPath(new GoalInverted(new GoalBlock(new BlockPos(x, y, z))));

// GoalComposite
GoalComposite composite = new GoalComposite(
    new GoalBlock(new BlockPos(x1, y1, z1)),
    new GoalBlock(new BlockPos(x2, y2, z2))
);
baritone.getCustomGoalProcess().setGoalAndPath(composite);
```

**Note:** Some goal classes may not exist in the exact Baritone version being used. If a class is missing, implement the equivalent behavior manually:
- `GoalXZ` → `GoalBlock(new BlockPos(x, 64, z))` with Y=64 as a reasonable surface guess
- `GoalYLevel` → `GoalBlock(new BlockPos(botX, y, botZ))` — path to current X,Z at target Y
- `GoalNear` → pick random offset within radius and use `GoalBlock`
- `GoalInverted` → compute retreat vector away from threat and use `GoalBlock`
- `GoalComposite` → manually find nearest candidate and use `GoalBlock`

Always check if the class exists by looking at the imports already in `FabricBaritoneBridge.java` or by checking the Baritone JAR.
