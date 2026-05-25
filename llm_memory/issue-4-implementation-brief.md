# Issue #4 Implementation Brief — Player / Entity Scanning

**Branch:** `issue/4-player-entity-scanning`
**Agent:** senior-engineer (Java)

---

## Already Scaffolded (do NOT modify)

- `core/src/main/java/com/mcagent/core/model/PlayerInfo.java` — DTO with `name`, `location`, `distance`, `direction`
- `core/src/main/java/com/mcagent/core/model/EntityInfo.java` — DTO with `type`, `location`, `distance`, `direction`
- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — 3 new methods added:
  - `PlayerInfo findPlayer(String playerName)`
  - `List<PlayerInfo> getNearbyPlayers(int radius)`
  - `List<EntityInfo> getNearbyEntities(String entityType, int radius)`

---

## Your Tasks

### 1. Implement `FabricBaritoneBridge.java`

Add implementations for the 3 new `BotOperations` methods. Use Minecraft client APIs inside `ClientThreadExecutor.execute()` (follow the existing pattern for thread safety).

**Reference implementation already exists:** `getPlayerPosition(String playerName)` at lines 184–198 shows exactly how to iterate `mc.level.entitiesForRendering()` and find a `Player` by name.

**Key requirements:**
- All methods must run inside `ClientThreadExecutor.execute()`
- `findPlayer(playerName)`: scan entities, match `instanceof Player` + name equalsIgnoreCase, compute distance and direction from bot's current position. Return `null` if not found.
- `getNearbyPlayers(radius)`: scan all `Player` entities within radius, exclude the bot itself (check `entity == mc.player`). Return list sorted by distance ascending.
- `getNearbyEntities(entityType, radius)`: scan all entities where `entity.getClass().getSimpleName().equalsIgnoreCase(entityType)` within radius. Return list sorted by distance ascending.
- **Direction calculation:** compute relative direction from bot to target. Use cardinal/intercardinal labels: "N", "NE", "E", "SE", "S", "SW", "W", "NW". Formula:
  ```
  double dx = target.x - bot.x;
  double dz = target.z - bot.z;
  double angle = Math.toDegrees(Math.atan2(-dx, dz)); // Minecraft yaw convention
  normalize to 0–360, map to 8 sectors of 45° each
  ```
- **Distance:** use `Location.distanceTo()` (euclidean 3D).

### 2. Add `@Tool` methods to `MinecraftTools.java`

Add 3 new tool methods following the exact style of existing tools (e.g. `getPlayerPosition`). Each should:
- Log at `info` level
- Call the appropriate `bot` method
- Return a concise, human-readable string suitable for chat

**Tool descriptions:**

```java
@Tool("Locate a player by name and report their coordinates, distance, and direction. Only works if the player is loaded.")
public String locatePlayer(@P("Player name") String playerName) { ... }
```
- If found: `"Player <name> is at (X, Y, Z), <dist> blocks <direction>"`
- If not found: `"I can't see <name> right now. They may be out of range or offline."`

```java
@Tool("List all nearby players within a radius, with coordinates, distance, and direction")
public String scanForPlayers(@P("Search radius in blocks") int radius) { ... }
```
- If empty: `"No players found within <radius> blocks."`
- Otherwise: one line per player, same format as locatePlayer

```java
@Tool("Scan for nearby mobs or animals of a specific type. Entity type examples: Creeper, Zombie, Pig, Cow, Skeleton, Spider.")
public String scanForEntities(@P("Entity type, e.g. Creeper, Zombie, Pig, Cow") String entityType,
                              @P("Search radius in blocks") int radius) { ... }
```
- If empty: `"No <type> found within <radius> blocks."`
- Otherwise: one line per entity, format: `"<Type> at (X, Y, Z), <dist> blocks <direction>"`

### 3. Update `Assistant.java` system prompt

Add the 3 new tools to the `<available_tools>` section. Add a brief `<entity_scanning_guidance>` section explaining:
- `locatePlayer` is for finding a specific player by name
- `scanForPlayers` is for discovering who is nearby
- `scanForEntities` filters by mob type (Creeper, Zombie, Pig, etc.)
- These are read-only — they do NOT move the bot

### 4. Update `TestRunner.java` mock

The inner class `MockBotOperations` must implement the 3 new interface methods. Return sensible mock data:
- `findPlayer("testplayer")` → `PlayerInfo.builder()...` with a nearby location
- `getNearbyPlayers(50)` → list containing one mock player
- `getNearbyEntities("Creeper", 50)` → list containing one mock creeper

Use `java.util.Collections.emptyList()` and `null` for edge cases as appropriate.

### 5. Add unit tests (if test structure allows)

In `LangChain4jServiceTest.java` or a new test file, add tests verifying:
- `MinecraftTools.locatePlayer` calls `bot.findPlayer` and formats output correctly
- `MinecraftTools.scanForPlayers` calls `bot.getNearbyPlayers` and formats output correctly
- `MinecraftTools.scanForEntities` calls `bot.getNearbyEntities` and formats output correctly

Use Mockito to mock `BotOperations` and verify method calls and return strings.

---

## Coding Standards (CRITICAL)

1. **Follow existing code style exactly.** Match indentation, naming, Lombok usage, and Spring patterns.
2. **All fabric-mod code must use `ClientThreadExecutor.execute()`** — never call Minecraft/Baritone APIs from a background thread.
3. **Use `dev.langchain4j.agent.tool.P` for parameter descriptions.**
4. **Keep methods small and single-purpose.** Extract helper methods for direction calculation, string formatting, etc.
5. **Do NOT modify existing tools or interface methods** — only ADD new ones.
6. **Do NOT change the return type of `Assistant.chat()`**.
7. **Build must pass:** after your changes, `./gradlew :core:compileJava` and `./gradlew :core:test` must succeed.

---

## Verification Checklist

- [ ] `BotOperations` interface compiles (3 new methods present)
- [ ] `FabricBaritoneBridge` implements all 3 methods with `ClientThreadExecutor`
- [ ] `MinecraftTools` has 3 new `@Tool` methods with proper `@P` annotations
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
5. `core/src/test/java/com/mcagent/core/service/LangChain4jServiceTest.java` (or new test file)

---

## Reference: Existing Direction Calculation Pattern

```java
private String calculateDirection(Location from, Location to) {
    double dx = to.x() - from.x();
    double dz = to.z() - from.z();
    double angle = Math.toDegrees(Math.atan2(-dx, dz));
    if (angle < 0) angle += 360;
    String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    int index = (int) Math.round(angle / 45) % 8;
    return dirs[index];
}
```
