# McAgent — Session Continuity Notes

**Date:** 2026-05-25
**Branch:** `issue/6-safety-mode-health-monitoring`
**Status:** Sprint #6 complete — validated in-game, PR opened

---

## Current State

Issues #3 (Message Queuing), #4 (Player/Entity Scanning), #5 (Advanced Pathing Goals), and #6 (Safety Mode & Health Monitoring) are all **implemented on this branch**.

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
- ✅ Safety mode: mob avoidance, no parkour/sprint, no block breaking
- ✅ Health monitoring: health, hunger, armor, threat detection
- ✅ Pathing behavior: careful / aggressive / default modes
- ✅ Block avoidance: configurable avoid-breaking list
- ✅ All tests passing
- ✅ Shadow JAR builds successfully

### Open Issues (backlog)
| Issue | Title | Priority | Blockers |
|-------|-------|----------|----------|
| #4 | Player / Entity Scanning | **Merged** | — |
| #5 | Advanced Pathing Goals | **Complete on branch** | — |
| #6 | Safety Mode & Health Monitoring | **Current sprint** | None |
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
