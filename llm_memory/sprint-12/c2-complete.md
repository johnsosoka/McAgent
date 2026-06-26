# Sprint 12 C2 — Interface Split Complete

**Date:** 2026-06-25  
**Branch:** `feature/sprint-12-survival`  
**Build status:** `:core:test` green, `:fabric-mod:test` green, `:fabric-mod:shadowJar` green

---

## Files Changed

### New (5 role interfaces under `core/src/main/java/com/mcagent/core/service/ops/`)
- `NavigationOperations.java` — 9 methods: `navigateTo*`, `followPlayer`, `mine`, `exploreNear`, `fleeFrom`, `navigateToNearest`
- `ObservationOperations.java` — 7 methods: `getCurrentPosition`, `getPlayerPosition`, `findPlayer`, `getNearbyPlayers`, `getNearbyEntities`, `getHealthStatus`, `getNearbyThreats`
- `InventoryOperations.java` — 3 methods: `hasItem`, `countItem`, `getInventorySummary`
- `BuildingOperations.java` — 2 methods: `buildPlatform`, `placeBlock`
- `BotControlOperations.java` — 8 methods: `cancel`, `pause`, `resume`, `setSafetyMode`, `setPathingBehavior`, `addBlockToAvoid`, `clearAvoidedBlocks`, `setProgressCallback`

### Modified
- `core/.../service/BotOperations.java` — reduced to a composite marker `extends` all five role interfaces. Inner records (`Location`, `HealthStatus`, `ThreatInfo`) kept here as a shared hub (they're referenced across multiple role interfaces; moving them would require circular imports).
- `fabric-mod/.../FabricBaritoneBridge.java` — `implements BotOperations, NavigationOperations, ObservationOperations, InventoryOperations, BuildingOperations, BotControlOperations` (explicit for readability; `BotOperations` alone would suffice).
- `fabric-mod/.../observer/AutonomousObserver.java` — constructor and field narrowed from `BotOperations` to `ObservationOperations` (the only role it actually uses).

### New Tests
- `core/.../service/BotOperationsSplitTest.java` — verifies `BotOperations` extends exactly the five role interfaces.
- `fabric-mod/.../FabricBaritoneBridgeSplitTest.java` — verifies `FabricBaritoneBridge` is assignable to all five role interfaces and the composite marker.

---

## Consumer-Narrowing Tradeoffs

**`MinecraftTools` kept on `BotOperations`:** The tools class uses methods from all five roles (navigation, observation, inventory, building, control). Splitting it to multiple injected interfaces would require changing the `@RequiredArgsConstructor` to a five-parameter constructor with no ergonomic gain — the class is a legitimate full-capability consumer. Left as `final BotOperations bot`.

**`AutonomousObserver` narrowed to `ObservationOperations`:** This is the clean win identified in MAJOR-1. The observer only reads world state; it never navigates, builds, or controls Baritone settings. The narrowed type makes that contract explicit and prevents accidental coupling to mutable operations.

**`FabricModConfig` unchanged:** The `@Bean BotOperations botOperations()` method continues to return `new FabricBaritoneBridge()`, which implements `BotOperations` (and through it, all five roles). No bean wiring changes required.
