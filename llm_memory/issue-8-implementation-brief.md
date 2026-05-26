# Issue #8 — Building / Placement Implementation Brief

**Branch:** `issue/8-building-placement`  
**Date:** 2026-05-25  
**Status:** Sprint started, branch cut from latest `main`

---

## Goal

Move the bot from gatherer to builder by exposing Baritone's `IBuilderBehavior` and basic block placement.

## Use Cases

- "Build a 5x5 platform of oak planks at my feet"
- "Place a torch here"
- "Build my house from my_house.schematic"

---

## Interface Targets

### BotOperations.java

```java
/**
 * Build a filled rectangular platform/area with a specific block type.
 * The bot must have the required blocks in inventory.
 * allowPlace is automatically enabled during the build.
 */
PathResult buildPlatform(int x1, int y1, int z1, int x2, int y2, int z2, String blockType);

/**
 * Place a single block at the specified coordinates.
 * The bot must have the block in its inventory (hotbar or main).
 * allowPlace is automatically enabled.
 */
PathResult placeBlock(int x, int y, int z, String blockType);
```

**OUT OF SCOPE for this sprint:** `buildSchematic(String schematicPath, int x, int y, int z)` — schematics require file I/O, parsing, and significant testing. Will be a follow-up enhancement.

### MinecraftTools.java

```java
@Tool("Build a filled rectangular area with a specific block type. Provide two opposite corners.")
public String buildArea(
    @P("Corner 1 X") int x1,
    @P("Corner 1 Y") int y1,
    @P("Corner 1 Z") int z1,
    @P("Corner 2 X") int x2,
    @P("Corner 2 Y") int y2,
    @P("Corner 2 Z") int z2,
    @P("Block type, e.g. minecraft:oak_planks") String blockType) { ... }

@Tool("Place a single block at specific coordinates. The bot must have the block in inventory.")
public String placeBlockAt(
    @P("X coordinate") int x,
    @P("Y coordinate") int y,
    @P("Z coordinate") int z,
    @P("Block type, e.g. minecraft:torch") String blockType) { ... }
```

---

## Baritone API Reference

```java
IBuilderBehavior builder = baritone.getBuilderBehavior();
builder.build(ISchematic schematic, BlockPos origin);
```

For simple fills:
```java
ISchematic schematic = new FillSchematic(width, height, length, block);
builder.build(schematic, origin);
```

**Relevant settings:**
- `settings.allowPlace` — must be `true` for building
- Baritone will automatically path to placement locations and place blocks

---

## Implementation Plan

### Phase 1: Scaffold Core Interface (Lead)
- Add `buildPlatform` and `placeBlock` to `BotOperations.java`
- Add `@Tool` methods to `MinecraftTools.java`
- Update `Assistant.java` system prompt with new tools + `<building_guidance>`
- Update `TestRunner.java` mock implementations

### Phase 2: FabricBaritoneBridge Implementation (Senior Engineer)
- Implement `buildPlatform` using `FillSchematic`
  - Resolve block type via existing `resolveBlock()` helper
  - Compute width/height/length from corners
  - Enable `allowPlace`, construct `FillSchematic`, call `builder.build()`
  - Wrap in `ClientThreadExecutor.execute()`
- Implement `placeBlock` using `FillSchematic(1, 1, 1, block)`
  - Single-block fill at target coordinate
  - Same `allowPlace` handling

### Phase 3: Material Verification (Senior Engineer)
- Before building, call `hasItem()` to verify the bot has enough blocks
- Volume = width * height * length
- If insufficient: return error `PathResult` with count details
- Do NOT auto-gather materials (out of scope)

### Phase 4: Tests (Junior Engineer)
- Add unit tests in `MinecraftToolsTest.java`
  - `buildArea_success` — mocks `buildPlatform` returning success
  - `buildArea_insufficientMaterials` — mocks `hasItem` returning false
  - `placeBlock_success` — mocks `placeBlock` returning success
  - `placeBlock_failure` — mocks `placeBlock` returning error

### Phase 5: Safety & Prompt Updates (Lead)
- Add `<building_guidance>` to `Assistant.java`:
  - `buildArea` requires two opposite corners and a block type
  - `placeBlockAt` places a single block at exact coordinates
  - Material verification happens automatically — bot will warn if short
  - `allowPlace` is enabled automatically for builds
  - Large builds can take time; use `sendMessage` to report progress
  - The LLM can chain multiple `buildArea`/`placeBlockAt` calls to construct complex structures
- Ensure `SafetyValidator` is aware of building commands (if applicable)

---

## Architectural Decision: LLM as Designer (for now)

**Decision:** Issue #8 delivers the *builder primitives* only. The LLM itself serves as the dynamic designer, breaking complex requests into sequences of `buildArea`/`placeBlockAt` calls.

**Rationale:**
- Keeps Issue #8 focused and shippable
- The LLM already reasons well about spatial decomposition (floor → walls → door → roof)
- Builder primitives must exist before any higher-level planning layer can function
- We can evaluate the LLM's real-world building performance with in-game validation

**Future Work (tracked as follow-up enhancement):**
If the LLM struggles with complex 3D structures (stairs, arches, roofs), we will add a dedicated `DesignService` in `core/` that generates structured `BuildPlan` objects. This would separate *design* from *execution*, enabling template libraries, caching, and specialized spatial reasoning algorithms.

**When to revisit:** After Issue #8 primitives are validated in-game and we have concrete examples of builds the LLM struggles with.

---

---

## Safety Considerations

- `allowPlace` must be enabled for any build command
- In multiplayer, accidental placement can grief structures
- Material check prevents starting builds the bot cannot complete
- Start with `FillSchematic` (small areas) before tackling external schematic files
- Large builds may take significant time — warn the player

---

## Acceptance Criteria

- [ ] `buildPlatform` constructs a filled rectangular area via `FillSchematic`
- [ ] `placeBlock` places a single block at coordinates via `FillSchematic(1,1,1)`
- [ ] Material check warns if insufficient blocks in inventory
- [ ] `allowPlace` automatically enabled for build commands
- [ ] LLM tools exposed with clear descriptions
- [ ] Integration tests for builds
- [ ] In-game validation passed
- [ ] Merge to `main` (pending human approval)

---

## Files to Modify

- `core/src/main/java/com/mcagent/core/service/BotOperations.java`
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java`
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`
- `core/src/main/java/com/mcagent/core/service/Assistant.java`
- `core/src/test/java/com/mcagent/core/TestRunner.java`
- `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java`
- `llm_memory/issue-8-implementation-brief.md` (this file)
- `llm_memory/logbook.md`
- `llm_memory/session-continuity-notes.md`

---

## Blockers

- ✅ Inventory queries (#7) merged — material verification is now possible
- None remaining

---

## Next in Queue

- **Issue #10** — Background Observation Loop (autonomous threat detection)
  - Needs #5–#8 capabilities to be actionable
  - Will be ready after #8 is merged
