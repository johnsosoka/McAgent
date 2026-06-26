# C5 — Death/Respawn Handler — Complete

**Branch:** `feature/sprint-12-survival`
**Status:** Done. All tests green.

## What was implemented

### `JobTracker` extensions
- Added `Death(Location, Instant, String suspendedJobDescription)` record using `BotOperations.Location` (Minecraft-free).
- Added `recordDeath(Location, Job)` and `getLastDeath()` methods.

### `DeathDetector` (new class)
- `fabric-mod/.../observer/DeathDetector.java` — pure state machine, no Minecraft imports.
- Tracks `wasAlive` boolean. Returns typed `DeathEvent` or `RespawnEvent` on transition edges; null otherwise.

### `AutonomousObserver` changes
- New 8-arg full constructor adds `JobTracker`, `Counters`, `RunLog`, `BotControlOperations`, and a `Supplier<PlayerState>`.
- `PlayerState` is a nested record `(boolean isDead, int x, int y, int z)` — keeps Minecraft out of `onTick()` logic.
- Legacy 3-arg constructor retained (deprecated) with no-op supplier, so existing tests compile/run without Minecraft.
- `checkDeath()` runs every tick unconditionally; calls `handleDeath()` or `handleRespawn()` on transitions.
- Death: records to `JobTracker`, increments `bot.deaths` counter, logs to `RunLog`, publishes framework message, cancels Baritone.
- Respawn: logs to `RunLog`, publishes framework message. No auto-resume.

### `FabricChatHandler.buildStatusString()`
- Appends `| Death: (x,y,z)` when a death was recorded this session. Full string truncated to 250 chars.

### `McAgentFabricMod`
- Updated to use full 8-arg `AutonomousObserver` constructor, providing a lambda that reads `Minecraft.getInstance().player` state.

## Tests
- `JobTrackerTest`: 4 new tests (`getLastDeath_returnsEmptyByDefault`, `recordDeath_setsLastDeath`, `recordDeath_capturesSuspendedJobDescription`, `recordDeath_overwritesPreviousDeath`).
- `DeathDetectorTest`: 7 tests covering all state transitions.

## Side-fixes required (C3 artifact bugs)
- `FabricBaritoneBridge.java`: removed unused `import net.minecraft.resources.ResourceLocation` (invalid in MC 26.1.2), replaced `getDayTime()` → `getDefaultClockTime()`, `inv.selected` → `inv.getSelectedSlot()/setSelectedSlot()`, fixed `ServerboundUseItemPacket` constructor (added sequence number param).
- `TestRunner.java` `MockBotOperations`: added stubs for `getWorldTime()`, `isNight()`, `getLightLevel()`, `eat()`, `placeTorch()`, `pickupNearbyItems()` (C3 interface additions).
