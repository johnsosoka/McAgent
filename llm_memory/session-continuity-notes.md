# McAgent — Session Continuity Notes

**Date:** 2026-05-25
**Branch:** `issue/4-player-entity-scanning`
**Status:** Sprint #4 implementation complete — awaiting in-game validation

---

## Current State

Issue #3 (Message Queuing & Event Harness) is **complete and merged to `main`** via PR #9.

### What Works (validated in-game)
- ✅ Message queuing (`BotEventQueue`) with priority scheduling
- ✅ Framework throttling & deduplication (`FrameworkMessageBuffer`)
- ✅ Thread safety via `ClientThreadExecutor` — all Baritone calls dispatched to client thread
- ✅ `.env` loading from Minecraft config directory (`EnvLoader`)
- ✅ Player context injection with pronoun resolution (`getPlayerPosition()`)
- ✅ Immediate tool chat feedback (no LLM latency)
- ✅ Disconnect debounce (3s) — no false shutdowns on dimension changes
- ✅ NPE hardening after shutdown
- ✅ All 20 core tests passing
- ✅ Shadow JAR builds successfully
- ✅ Clean in-game validation: `come here`, `stop following`, `what is my location`

### Open Issues (backlog)
| Issue | Title | Priority | Blockers |
|-------|-------|----------|----------|
| #4 | Player / Entity Scanning | **Current sprint** | None |
| #5 | Advanced Pathing Goals | Pending #4 | #4 (fleeing needs entity location) |
| #6 | Safety Mode & Health Monitoring | Pending #4, #5 | #4 (nearby threats), #5 (GoalInverted) |
| #7 | Inventory Queries | Pending #5 | None (can be done anytime) |
| #8 | Building / Placement | Pending #6 | #6 (safety confirmation) |

### Architecture Decisions (unchanged)
- Tool-based architecture: `Assistant.chat()` returns `String`, LangChain4j auto-discovers `@Tool` methods
- `BotOperations` interface remains synchronous; thread safety enforced at `FabricBaritoneBridge` implementation layer
- `FabricChatSender` is the final rate-limited, thread-safe chat transport
- Memory backpressure: `max-history: 20` in `application.yml`

---

## Active Sprint: Issue #4 — Player / Entity Scanning

**Branch:** `issue/4-player-entity-scanning`
**Commit:** `c7e3923`

### Implementation Status
- ✅ `BotOperations` interface — 3 new methods defined
- ✅ `PlayerInfo` / `EntityInfo` DTOs — immutable Lombok builders
- ✅ `FabricBaritoneBridge` — all methods wrapped in `ClientThreadExecutor.execute()`
- ✅ `MinecraftTools` — 3 new `@Tool` methods with proper `@P` annotations
- ✅ `Assistant` system prompt — updated with new tools + entity scanning guidance
- ✅ `TestRunner` mock — implements new interface methods
- ✅ `MinecraftToolsTest` — 6 unit tests (found / not-found for each tool)
- ✅ Build & tests — 26 tests passing, core + fabric-mod compile green
- ✅ Code review — passed (direction formula bug caught and fixed)

### Remaining before merge
- [ ] In-game validation (deploy JAR, test `agent where is Steve?`, `agent scan for creepers`)
- [ ] Human approval for merge to `main`

### Interface Targets (all implemented)
**BotOperations.java:**
```java
PlayerInfo findPlayer(String playerName);
List<PlayerInfo> getNearbyPlayers(int radius);
List<EntityInfo> getNearbyEntities(String entityType, int radius);
```

**MinecraftTools.java:**
```java
@Tool("Locate a player by name and report coordinates, distance, and direction")
public String locatePlayer(@P("Player name") String playerName) { ... }

@Tool("List nearby players within a radius")
public String scanForPlayers(@P("Search radius in blocks") int radius) { ... }

@Tool("Scan for nearby mobs or animals of a specific type")
public String scanForEntities(@P("Entity type, e.g. Creeper, Zombie, Pig, Cow") String entityType,
                              @P("Search radius in blocks") int radius) { ... }
```

### Safety Notes
- Read-only operations — no movement triggered
- Uses `Minecraft.getInstance().level.entitiesForRendering()` for scanning
- Direction calculated with 8-sector compass (N, NE, E, SE, S, SW, W, NW)

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
**For:** John Sosoka — Issue #4 sprint continuation
