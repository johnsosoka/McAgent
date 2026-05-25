# McAgent — Session Continuity Notes

**Date:** 2026-05-25
**Branch:** `issue/5-advanced-pathing-goals`
**Status:** Sprint #5 started — Issue #4 merged to main

---

## Current State

Issue #3 (Message Queuing) and #4 (Player/Entity Scanning) are **both complete and merged to `main`**.

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
- ✅ All 26 core tests passing
- ✅ Shadow JAR builds successfully

### Open Issues (backlog)
| Issue | Title | Priority | Blockers |
|-------|-------|----------|----------|
| #4 | Player / Entity Scanning | **Merged** | None |
| #5 | Advanced Pathing Goals | **Current sprint** | None |
| #6 | Safety Mode & Health Monitoring | Pending #5 | #5 (`fleeFrom` needs `GoalInverted`) |
| #7 | Inventory Queries | Can be parallel | None |
| #8 | Building / Placement | Pending #6 | #6 (safety confirmation) |
| #10 | Background Observation Loop | Future | Needs #5–#8 capabilities |

### Architecture Decisions (unchanged)
- Tool-based architecture: `Assistant.chat()` returns `String`, LangChain4j auto-discovers `@Tool` methods
- `BotOperations` interface remains synchronous; thread safety enforced at `FabricBaritoneBridge` implementation layer
- `FabricChatSender` is the final rate-limited, thread-safe chat transport
- Memory backpressure: `max-history: 20` in `application.yml`

---

## Active Sprint: Issue #5 — Advanced Pathing Goals

**Branch:** `issue/5-advanced-pathing-goals`

### Goals
- `navigateToXZ(x, z)` — path to surface coordinates (any Y)
- `navigateToYLevel(y)` — path to specific depth (strip mining)
- `exploreNear(center, radius)` — explore within radius
- `fleeFrom(threat, safeDistance)` — retreat using `GoalInverted`
- `navigateToNearest(candidates)` — nearest of multiple waypoints (stretch)

### Interface Targets
**BotOperations.java:**
```java
PathResult navigateToXZ(int x, int z);
PathResult navigateToYLevel(int y);
PathResult exploreNear(Location center, int radius);
PathResult fleeFrom(Location threat, int safeDistance);
PathResult navigateToNearest(List<Location> candidates);
```

**MinecraftTools.java:**
```java
@Tool("Navigate to surface X,Z coordinates (any Y level)")
public String navigateToSurface(@P("X coordinate") int x, @P("Z coordinate") int z) { ... }

@Tool("Go to a specific Y level, useful for strip mining")
public String goToDepth(@P("Target Y level") int y) { ... }

@Tool("Explore within a radius of a center point")
public String exploreArea(@P("Center X") int x, @P("Center Y") int y, @P("Center Z") int z,
                          @P("Radius in blocks") int radius) { ... }

@Tool("Flee from specific coordinates to maintain a safe distance")
public String fleeFrom(@P("Threat X") int x, @P("Threat Y") int y, @P("Threat Z") int z,
                       @P("Safe distance in blocks") int distance) { ... }
```

### Technical Notes
- Pure Baritone API — import goal classes (`GoalXZ`, `GoalYLevel`, `GoalNear`, `GoalInverted`, `GoalComposite`)
- All implementations wrapped in `ClientThreadExecutor.execute()`
- `fleeFrom` uses `GoalInverted(new GoalBlock(threatPos))` or safe-direction vector
- `exploreNear` uses `GoalNear` or `GoalXZ` with random offset within radius

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
**For:** John Sosoka — Issue #5 sprint continuation
