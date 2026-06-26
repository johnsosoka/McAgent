# Sprint 12 Code-Review Follow-Up — Complete

All nine findings resolved. Both `:core:test` and `:fabric-mod:test` green; `:fabric-mod:shadowJar` builds clean.

## Fix Summary

**Fix 1 (CRITICAL) — `eat()` optimistic return**
`FabricBaritoneBridge.eat()` now returns `true` immediately after selecting a valid food slot and dispatching `useItem`. Removed the same-tick `getFoodLevel()` check (always false due to server-tick latency). Javadoc updated on `BotControlOperations.eat()` documenting best-effort semantics. Sprint 13 async verification deferred per spec.

**Fix 2 (MAJOR) — Mutable Snapshot lists**
`WorldStateUpdater.snapshot()` now wraps `scanned.entities()`, `scanned.threats()`, and `scanned.players()` with `List.copyOf()` at construction. `Snapshot.EMPTY` already uses `List.of()` (unchanged). New test `WorldStateCacheTest.snapshotListsAreImmutable` verifies `UnsupportedOperationException` on `cached.threats().add(...)`.

**Fix 3 (MAJOR) — `pickupNearbyItems` single GoalComposite**
Replaced the per-item `setGoalAndPath` loop with a single `GoalComposite` of `GoalNear` entries, set once. Existing tests cover the delegation path; `pickupNearbyItems_returnsCountString` already verifies the count string.

**Fix 4 (MAJOR) — `HOSTILE_MOBS` deduplication**
New `com.mcagent.fabric.HostileMobs` (`public final`) holds the canonical `Set<String> NAMES`. Both `FabricBaritoneBridge` and `WorldStateUpdater` now delegate to `HostileMobs.NAMES`; inline sets and the `Set` imports removed from both.

**Fix 5 (MAJOR) — Reflex Rule 1b, critical HP no threat**
`SurvivalReflexes.Action` gains `CRITICAL_NO_THREAT`. Rule 1 split into 1a (threats present → FLEE) and 1b (no threats → safety mode + `CRITICAL_NO_THREAT`). `AutonomousObserver.scan()` publishes a framework message when 1b fires, keeping `SurvivalReflexes` free of fabric-mod dependencies. New test `hpCritical_noThreats_returnsCriticalNoThreat` added; `rule1_criticalHpNoThreats_doesNotFlee` tightened to only assert on FLEE/fleeFrom.

**Fix 6 (MINOR) — Unused imports in FabricBaritoneBridge**
Removed `GoalInverted`, `Inventory`, and `Set` imports.

**Fix 7 (MINOR) — Dead `inventorySummary` field**
Removed `String inventorySummary` from `ReflexSnapshot`. Javadoc updated to explain that inventory checks go via `bot.hasItem(...)` directly. `AutonomousObserver` constructor call and `SurvivalReflexesTest` helper updated (dropped 6-arg → 5-arg).

**Fix 8 (MINOR) — Rule 2 fall-through comment**
Added explanatory comment before Rule 3 in `SurvivalReflexes.tickReflexes()`.

**Fix 9 (MINOR) — Config-driven cache scan radius**
`McAgentFabricMod.CACHE_SCAN_RADIUS` constant replaced with instance field `cacheScanRadius` (default 32). After Spring context starts, `cacheScanRadius = max(threatRadius, passiveRadius)` from `BotProperties.ObservationProperties`. Field reset to default on shutdown.
