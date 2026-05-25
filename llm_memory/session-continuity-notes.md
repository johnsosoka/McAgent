# McAgent — Session Continuity Notes

**Date:** 2026-05-25
**Branch:** `issue/6-safety-mode-health-monitoring`
**Status:** Sprint #6 started — Issue #6 scope expanded with respectful pathing

---

## Current State

Issues #3 (Message Queuing), #4 (Player/Entity Scanning), and #5 (Advanced Pathing Goals) are **complete and merged or ready for merge**.

### What Works (validated in-game)
- ✅ Message queuing (`BotEventQueue`) with priority scheduling
- ✅ Framework throttling & deduplication (`FrameworkMessageBuffer`)
- ✅ Thread safety via `ClientThreadExecutor`
- ✅ `.env` loading from Minecraft config directory (`EnvLoader`)
- ✅ Player context injection with pronoun resolution
- ✅ Immediate tool chat feedback (no LLM latency)
- ✅ Disconnect debounce (3s)
- ✅ NPE hardening after shutdown
- ✅ Player / entity scanning (`locatePlayer`, `scanForPlayers`, `scanForEntities`)
- ✅ 8-sector compass direction calculation
- ✅ Advanced pathing: surface X,Z, Y-level depth, exploration radius, fleeing, nearest waypoint
- ✅ All 33 core tests passing
- ✅ Shadow JAR builds successfully

### Open Issues (backlog)
| Issue | Title | Priority | Blockers |
|-------|-------|----------|----------|
| #4 | Player / Entity Scanning | **Merged** | — |
| #5 | Advanced Pathing Goals | **Complete on branch** | — |
| **#6** | Safety Mode & Health Monitoring | **Current sprint** | None |
| #7 | Inventory Queries | Can be parallel | None |
| #8 | Building / Placement | Pending #6 | #6 (safety confirmation) |
| #10 | Background Observation Loop | Future | Needs #5–#8 capabilities |

### Architecture Decisions (unchanged)
- Tool-based architecture: `Assistant.chat()` returns `String`, LangChain4j auto-discovers `@Tool` methods
- `BotOperations` interface remains synchronous; thread safety enforced at `FabricBaritoneBridge` implementation layer
- `FabricChatSender` is the final rate-limited, thread-safe chat transport
- Memory backpressure: `max-history: 20` in `application.yml`

---

## Active Sprint: Issue #6 — Safety Mode & Health Monitoring

**Branch:** `issue/6-safety-mode-health-monitoring`

### Goals
- `setSafetyMode(boolean)` — toggles mob avoidance, parkour, sprint, block breaking, door usage
- `getHealthStatus()` — reports health, hunger, basic status
- `getNearbyThreats(radius)` — lists hostile mobs with distance/direction
- `setPathingBehavior(mode)` — "careful" (no breaking, uses doors), "aggressive", "default"
- `avoidBreakingBlock(blockType)` — add block to avoid-breaking list
- `clearBlockAvoidance()` — reset avoidance rules

### Why This Sprint
- Both #6 blockers are resolved:
  - #4 (entity scanning) → `getNearbyThreats()` can use `scanForEntities`
  - #5 (pathing) → `fleeFrom()` is available for retreat
- **Expanded scope:** Added "respectful pathing / house mode" — the bot can use doors and avoid breaking player-built blocks
- Prevents property damage and makes the bot a better companion

### Interface Targets
**BotOperations.java:**
```java
void setSafetyMode(boolean enabled);
HealthStatus getHealthStatus();
List<ThreatInfo> getNearbyThreats(int radius);
void setPathingBehavior(String mode); // "careful", "aggressive", "default"
void addBlockToAvoid(String blockType);
void clearAvoidedBlocks();
```

**MinecraftTools.java:**
```java
@Tool("Enable or disable safe mode (mob avoidance, no parkour, no sprint, no block breaking, uses doors)")
public String setSafetyMode(@P("true to enable safe mode, false for normal") boolean enabled) { ... }

@Tool("Report current health, hunger, and any nearby threats")
public String getStatusReport() { ... }

@Tool("Set pathing behavior mode. 'careful' avoids breaking blocks and uses doors. 'aggressive' allows breaking for speed. 'default' restores normal settings.")
public String setPathingBehavior(@P("Behavior mode: careful, aggressive, or default") String mode) { ... }

@Tool("Add a block type to the avoid-breaking list. Examples: minecraft:glass, minecraft:oak_planks")
public String avoidBreakingBlock(@P("Block ID to avoid breaking") String blockType) { ... }

@Tool("Clear all custom block avoidance rules and restore defaults")
public String clearBlockAvoidance() { ... }
```

### Behavior Modes

| Mode | allowBreak | allowParkour | allowSprint | allowOpenDoors | blocksToAvoidBreaking |
|------|-----------|-------------|-------------|---------------|----------------------|
| **careful** | false | false | false | true | common building blocks |
| **aggressive** | true | true | true | false | empty |
| **default** | true | true | true | true | empty |

### Technical Notes
- Baritone settings accessed via `baritone.getSettings()`
- `blocksToAvoidBreaking` accepts a list of `Block` instances
- Health via `Minecraft.getInstance().player.getHealth()` and `getFoodData().getFoodLevel()`
- Threat detection reuses `scanForEntities` logic from Issue #4

---

## Build Commands
```bash
# Full build + tests
./gradlew build

# Just the mod JAR
./gradlew :fabric-mod:shadowJar

# Install to Minecraft
./gradlew :fabric-mod:shadowJar && \
  cp fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar \
  ~/Library/Application\ Support/minecraft/mods/

# Check logs
cat ~/Library/Application\ Support/minecraft/logs/latest.log | grep -i 'mcagent\|Tool:\|Error'
```

## Key Dependencies
- Minecraft: 26.1.2 (mojmap)
- Fabric Loader: 0.19.2
- Baritone: 1.17.0 (unoptimized fabric fork)
- Spring Boot: 3.4.0
- LangChain4j: 1.15.0
- Java: 25 (fabric-mod), 21 (core tests)
- Gradle: 8.14.5

---
**Prepared by:** AI Lead (OpenCode)
**For:** John Sosoka — Issue #6 sprint continuation
