# Issue #6 Implementation Brief — Safety Mode & Health Monitoring

**Branch:** `issue/6-safety-mode-health-monitoring`
**Agent:** senior-engineer (Java)

---

## Already Scaffolded (do NOT modify)

- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — New methods and records added:
  - `void setSafetyMode(boolean enabled)`
  - `HealthStatus getHealthStatus()`
  - `List<ThreatInfo> getNearbyThreats(int radius)`
  - `void setPathingBehavior(String mode)`
  - `void addBlockToAvoid(String blockType)`
  - `void clearAvoidedBlocks()`
  - `record HealthStatus(float health, float maxHealth, int foodLevel, int armorLevel)`
  - `record ThreatInfo(String type, Location location, double distance, String direction)`

---

## Your Tasks

### 1. Implement `FabricBaritoneBridge.java`

Add implementations for the 6 new `BotOperations` methods. Use Minecraft client APIs inside `ClientThreadExecutor.execute()` (follow the existing pattern for thread safety).

**Key requirements:**
- All methods must run inside `ClientThreadExecutor.execute()`
- `setSafetyMode(true)`:
  - `baritone.getSettings().allowBreak.value = false`
  - `baritone.getSettings().allowParkour.value = false`
  - `baritone.getSettings().allowSprint.value = false`
  - `baritone.getSettings().allowOpenDoors.value = true`
  - `baritone.getSettings().avoidance.value = true`
  - `baritone.getSettings().mobAvoidanceRadius.value = 16.0`
  - (Optional) populate `blocksToAvoidBreaking` with common building blocks
- `setSafetyMode(false)` — restore all defaults
- `getHealthStatus()`:
  - Read `Minecraft.getInstance().player.getHealth()`
  - Read `Minecraft.getInstance().player.getFoodData().getFoodLevel()`
  - Read `Minecraft.getInstance().player.getArmorValue()`
  - `Minecraft.getInstance().player.getMaxHealth()` for max health
  - Return `new HealthStatus(health, maxHealth, foodLevel, armorValue)`
- `getNearbyThreats(radius)`:
  - Reuse the entity scanning pattern from `getNearbyEntities`
  - Filter for hostile mobs: `Creeper`, `Zombie`, `Skeleton`, `Spider`, `Enderman`, `Witch`, `Drowned`, `Husk`, `Stray`, `WitherSkeleton`, `Blaze`, `Ghast`, `PiglinBrute`, `Vindicator`, `Evoker`, `Ravager`
  - Use `entity.getClass().getSimpleName()` to match
  - Return `List<ThreatInfo>` with type, location, distance, direction
  - Sort by distance ascending
- `setPathingBehavior(mode)`:
  - `"careful"`: `allowBreak=false`, `allowParkour=false`, `allowSprint=false`, `allowOpenDoors=true`, populate `blocksToAvoidBreaking` with common blocks (glass, planks, stone bricks, etc.)
  - `"aggressive"`: `allowBreak=true`, `allowParkour=true`, `allowSprint=true`, `allowOpenDoors=false`, clear `blocksToAvoidBreaking`
  - `"default"`: restore Baritone defaults (`allowBreak=true`, `allowParkour=true`, `allowSprint=true`, `allowOpenDoors=true`, clear `blocksToAvoidBreaking`)
  - Log the mode change
- `addBlockToAvoid(blockType)`:
  - Resolve block ID to `Block` instance using the same pattern as `resolveBlock()` in `FabricBaritoneBridge`
  - Add to `baritone.getSettings().blocksToAvoidBreaking.value`
  - If block not found, log warning
- `clearAvoidedBlocks()`:
  - Clear `baritone.getSettings().blocksToAvoidBreaking.value` list
  - Log confirmation

**Baritone settings access pattern:**
```java
baritone.getSettings().allowBreak.value = false;
```

**Note:** `blocksToAvoidBreaking` is typically a `List<Block>`. You may need to cast or check the actual type in the Baritone 1.17.0 API.

### 2. Add `@Tool` methods to `MinecraftTools.java`

Add 5 new tool methods following the exact style of existing tools. Each should:
- Log at `info` level
- Call the appropriate `bot` method
- Return a concise, human-readable string suitable for chat
- For `setSafetyMode` and `setPathingBehavior`, send immediate chat feedback confirming the mode change

**Tool descriptions:**

```java
@Tool("Enable or disable safe mode. Safe mode prevents block breaking, disables parkour/sprint, enables mob avoidance, and allows door usage.")
public String setSafetyMode(@P("true to enable safe mode, false for normal") boolean enabled) { ... }
```
- On enable: chat "Safe mode enabled. I'll be careful."
- On disable: chat "Safe mode disabled. Normal behavior restored."
- Return: "Safe mode enabled" / "Safe mode disabled"

```java
@Tool("Report the bot's current health, hunger, armor, and any nearby threats.")
public String getStatusReport() { ... }
```
- Calls `bot.getHealthStatus()` and `bot.getNearbyThreats(32)`
- Format: "Status: <health>. Nearby threats: <list or 'none'>"
- Example: "Status: Health: 18/20, Food: 15/20, Armor: 8. Nearby threats: Creeper at (100, 64, 100), 12 blocks NE"

```java
@Tool("Set pathing behavior mode. 'careful' avoids breaking blocks and uses doors. 'aggressive' allows breaking for speed. 'default' restores normal settings.")
public String setPathingBehavior(@P("Behavior mode: careful, aggressive, or default") String mode) { ... }
```
- Validate mode is one of "careful", "aggressive", "default"
- Chat: "Pathing behavior set to <mode>"
- Return: "Pathing behavior set to <mode>"

```java
@Tool("Add a block type to the avoid-breaking list. The bot will path around these blocks instead of breaking them. Examples: minecraft:glass, minecraft:oak_planks")
public String avoidBreakingBlock(@P("Block ID to avoid breaking, e.g. minecraft:glass") String blockType) { ... }
```
- Call `bot.addBlockToAvoid(blockType)`
- Return: "Added <blockType> to avoid-breaking list" or "Could not find block: <blockType>"

```java
@Tool("Clear all custom block avoidance rules and restore defaults.")
public String clearBlockAvoidance() { ... }
```
- Call `bot.clearAvoidedBlocks()`
- Return: "Cleared all block avoidance rules."

### 3. Update `Assistant.java` system prompt

Add the 5 new tools to the `<available_tools>` section. Add a brief `<safety_guidance>` section explaining:
- `setSafetyMode` is for overall cautious behavior (no breaking, no parkour, mob avoidance)
- `setPathingBehavior` is for fine-tuned pathing control (careful/aggressive/default)
- `avoidBreakingBlock` and `clearBlockAvoidance` for protecting specific blocks
- `getStatusReport` for checking bot health and threats
- When the player says "be careful" or "don't break anything", use `setSafetyMode(true)` or `setPathingBehavior("careful")`
- When the player says "go inside" and a house is nearby, use `setPathingBehavior("careful")` first to avoid breaking windows

### 4. Update `TestRunner.java` mock

The inner class `MockBotOperations` must implement the 6 new interface methods. Return sensible mock data:
- `setSafetyMode(boolean)` — log and no-op
- `getHealthStatus()` — `new HealthStatus(18.0f, 20.0f, 15, 8)`
- `getNearbyThreats(32)` — `List.of(new ThreatInfo("Creeper", new Location(100, 64, 100), 12.0, "NE"))`
- `setPathingBehavior(mode)` — log and no-op
- `addBlockToAvoid(blockType)` — log and no-op
- `clearAvoidedBlocks()` — log and no-op

### 5. Add unit tests

Add tests in `MinecraftToolsTest.java` verifying:
- `setSafetyMode(true)` calls `bot.setSafetyMode(true)` and formats output
- `getStatusReport` calls `bot.getHealthStatus()` and `bot.getNearbyThreats()`, formats output
- `setPathingBehavior("careful")` calls `bot.setPathingBehavior("careful")`
- `avoidBreakingBlock` calls `bot.addBlockToAvoid`
- `clearBlockAvoidance` calls `bot.clearAvoidedBlocks`

Use Mockito. Keep tests simple and intention-revealing.

---

## Coding Standards (CRITICAL)

1. **Follow existing code style exactly.** Match indentation, naming, Lombok usage, and Spring patterns.
2. **All fabric-mod code must use `ClientThreadExecutor.execute()`** — never call Minecraft/Baritone APIs from a background thread.
3. **Use `dev.langchain4j.agent.tool.P` for parameter descriptions.**
4. **Keep methods small and single-purpose.** Extract helper methods for threat filtering, string formatting, etc.
5. **Do NOT modify existing tools or interface methods** — only ADD new ones.
6. **Do NOT change the return type of `Assistant.chat()`**.
7. **Build must pass:** after your changes, `./gradlew :core:compileJava` and `./gradlew :core:test` must succeed.

---

## Verification Checklist

- [ ] `BotOperations` interface compiles (6 new methods + records present)
- [ ] `FabricBaritoneBridge` implements all methods with `ClientThreadExecutor`
- [ ] `MinecraftTools` has 5 new `@Tool` methods with proper `@P` annotations
- [ ] `Assistant` system prompt lists the new tools + safety guidance
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
5. `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java`

---

## Reference: Baritone Settings API

```java
// Access settings
baritone.getSettings().allowBreak.value = false;
baritone.getSettings().allowParkour.value = false;
baritone.getSettings().allowSprint.value = false;
baritone.getSettings().allowOpenDoors.value = true;
baritone.getSettings().avoidance.value = true;
baritone.getSettings().mobAvoidanceRadius.value = 16.0;

// Block avoidance (type may vary — inspect Baritone JAR)
// Common pattern:
// baritone.getSettings().blocksToAvoidBreaking.value.add(block);
```

**Common building blocks for careful mode:**
- `Blocks.GLASS`, `Blocks.GLASS_PANE`
- `Blocks.OAK_PLANKS`, `Blocks.BIRCH_PLANKS`, `Blocks.SPRUCE_PLANKS`, `Blocks.JUNGLE_PLANKS`, `Blocks.ACACIA_PLANKS`, `Blocks.DARK_OAK_PLANKS`
- `Blocks.OAK_LOG`, `Blocks.BIRCH_LOG`, etc.
- `Blocks.STONE_BRICKS`, `Blocks.BRICKS`
- `Blocks.WHITE_WOOL`, etc.
