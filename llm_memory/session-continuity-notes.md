# McAgent — Session Continuity Notes

**Date:** 2026-05-27
**Branch:** `feature/issue-10-background-observation-loop`
**Status:** Sprint #10 complete — background observation loop implemented, all tests passing

---

## Current State

Issues #3–#10 are all **implemented**:
- #3 Message Queuing, #4 Player/Entity Scanning, #5 Advanced Pathing, #6 Safety/Health, #7 Inventory Queries, #8 Building/Placement, #10 Background Observation Loop

### What Works (validated in-game unless marked)
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
- ✅ Safety mode: mob avoidance, no parkour/sprint, no block breaking
- ✅ Health monitoring: health, hunger, armor, threat detection
- ✅ Pathing behavior: careful / aggressive / default modes
- ✅ Block avoidance: configurable avoid-breaking list
- ✅ Inventory queries: hasItem, countItem, getInventorySummary (validated in-game)
- ✅ Building primitives: buildArea, placeBlockAt (validated in-game — platform built successfully)
- ✅ Material verification before builds
- ✅ **Background observation loop** (`AutonomousObserver`) with tick-based scanning
- ✅ **Threat detection** (hostile mobs) and **opportunity detection** (passive mobs)
- ✅ **Debounce** by spatial bucket (4-block) + time window
- ✅ **Passive mode**: observations published as `<framework>` messages to `BotEventQueue`
- ✅ **Active mode**: observations trigger immediate LLM calls via `triggerUrgentFramework()` on urgent dispatcher thread
- ✅ **Player toggle commands**: `agent watch`, `agent stop watching`, `agent passive mode`, `agent active mode`
- ✅ **Configurable** via `BotProperties.ObservationProperties`
- ✅ All tests passing (59 — 48 core + 11 fabric-mod observer)
- ✅ Shadow JAR builds successfully

### Open Issues (backlog)
| Issue | Title | Priority | Blockers |
|-------|-------|----------|----------|
| #4 | Player / Entity Scanning | **Merged** | — |
| #5 | Advanced Pathing Goals | **Merged** | — |
| #6 | Safety Mode & Health Monitoring | **Merged** | — |
| #7 | Inventory Queries | **Merged** | None |
| #8 | Building / Placement | **Merged** | None |
| #10 | Background Observation Loop | **In Review** | None |

### Architecture Decisions
- Tool-based architecture: `Assistant.chat()` returns `String`, LangChain4j auto-discovers `@Tool` methods
- `BotOperations` interface remains synchronous; thread safety enforced at `FabricBaritoneBridge` implementation layer
- `FabricChatSender` is the final rate-limited, thread-safe chat transport
- Memory backpressure: `max-history: 20` in `application.yml`
- **NEW:** `AutonomousObserver` runs on the client thread, reads `BotOperations`, publishes to `BotEventQueue`
- **NEW:** `BotEventQueue` maintains a separate `urgentDispatcher` thread for active-mode LLM invocation, preventing blocking of player commands
- **NEW:** `LangChain4jService.processUrgentObservation()` injects framework context and calls `assistant.chat()` without player context injection
- **NEW:** Debounce uses 4-block spatial bucketing (`type:bucketX:bucketZ`) so moving mobs re-trigger, but stationary ones don't spam

---

## Completed Sprints

### Issue #10 — Background Observation Loop (in review)
**Branch:** `feature/issue-10-background-observation-loop`

### Goals
- `AutonomousObserver` — tick-based world scanning for threats and opportunities
- Configurable scan interval, threat radius, passive mob radius
- Debounce logic (spatial + temporal) to prevent spam
- Passive mode: observations as `<framework>` messages in chat memory
- Active mode: urgent LLM calls that can trigger tools like `fleeFrom()` or `setSafetyMode(true)`
- Player chat toggle commands: `watch`, `stop watching`, `passive mode`, `active mode`

### Interface Targets
**BotProperties.java:**
```java
ObservationProperties observation = new ObservationProperties();
// enabled, scanIntervalTicks, threatRadius, passiveRadius,
// mode (passive/active), messageMode (individual/summary),
// debounceSeconds, trackPassiveMobs, passiveMobTypes
```

**BotEventQueue.java:**
```java
void triggerUrgentFramework(String message);
```

**LangChain4jService.java:**
```java
String processUrgentObservation(String observation);
```

**AutonomousObserver.java:**
```java
void onTick();
void setEnabled(boolean enabled);
void setMode(String mode);
void setMessageMode(String messageMode);
```

---

### Issue #8 — Building / Placement (merged)
**PR:** #14

### Goals
- `buildPlatform(x1, y1, z1, x2, y2, z2, blockType)` — filled rectangular area via `FillSchematic`
- `placeBlock(x, y, z, blockType)` — single block placement at coordinates
- Material verification via `hasItem()` before building
- `allowPlace` automatically enabled for build commands

### Interface Targets (all implemented)
**BotOperations.java:**
```java
PathResult buildPlatform(int x1, int y1, int z1, int x2, int y2, int z2, String blockType);
PathResult placeBlock(int x, int y, int z, String blockType);
```

**MinecraftTools.java:**
```java
@Tool buildArea(x1, y1, z1, x2, y2, z2, blockType)
@Tool placeBlockAt(x, y, z, blockType)
```

---

### Issue #7 — Inventory Queries (merged)
**PR:** #13

### Goals
- `hasItem(itemId, count)` — check if bot has at least N of an item
- `countItem(itemId)` — count total of an item across inventory
- `getInventorySummary()` — return top items, truncated for chat

### Interface Targets (all implemented)
**BotOperations.java:**
```java
boolean hasItem(String itemId, int count);
int countItem(String itemId);
String getInventorySummary();
```

**MinecraftTools.java:**
```java
@Tool checkInventory(itemId, count)
@Tool getInventorySummary()
```

---

### Issue #6 — Safety Mode & Health Monitoring (merged)
**PR:** #12

### Goals
- `setSafetyMode(boolean)` — toggles mob avoidance, parkour, sprint, block breaking
- `getHealthStatus()` — reports health, hunger, armor
- `getNearbyThreats(radius)` — lists hostile mobs with distance/direction
- `setPathingBehavior(mode)` — "careful" (no breaking), "aggressive", "default"
- `avoidBreakingBlock(blockType)` — add block to avoid-breaking list
- `clearBlockAvoidance()` — reset avoidance rules

### Combined with Issue #5
This branch now also includes all Issue #5 features:
- `navigateToSurface(x, z)` — surface X,Z travel
- `goToDepth(y)` — vertical depth navigation
- `exploreArea(x, y, z, radius)` — exploration within radius
- `fleeFrom(x, y, z, distance)` — emergency retreat
- `navigateToNearestLocation(names)` — nearest waypoint selection

### Interface Targets (all implemented)
**BotOperations.java:**
```java
// Issue #5 — Advanced Pathing
PathResult navigateToXZ(int x, int z);
PathResult navigateToYLevel(int y);
PathResult exploreNear(Location center, int radius);
PathResult fleeFrom(Location threat, int safeDistance);
PathResult navigateToNearest(List<Location> candidates);

// Issue #6 — Safety & Health
void setSafetyMode(boolean enabled);
HealthStatus getHealthStatus();
List<ThreatInfo> getNearbyThreats(int radius);
void setPathingBehavior(String mode);
void addBlockToAvoid(String blockType);
void clearAvoidedBlocks();
```

### Behavior Modes

| Mode | allowBreak | allowParkour | allowSprint | blocksToAvoidBreaking |
|------|-----------|-------------|-------------|----------------------|
| **careful** | false | false | false | common building blocks |
| **aggressive** | true | true | true | empty |
| **default** | true | true | true | empty |

### Technical Notes
- Baritone settings accessed via `BaritoneAPI.getSettings()`
- `blocksToAvoidBreaking` accepts a list of `Block` instances
- Health via `Minecraft.getInstance().player.getHealth()` and `getFoodData().getFoodLevel()`
- Threat detection scans for 16 hostile mob types

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
