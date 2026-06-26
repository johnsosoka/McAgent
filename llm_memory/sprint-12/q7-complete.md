# Sprint 12 Q7 — Read-Only World State Cache — Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-12-survival`
**Build status:** `:core:test` green, `:fabric-mod:test` green, `:fabric-mod:shadowJar` green

---

## Files Added

- `fabric-mod/.../cache/WorldStateCache.java` — `volatile Snapshot` reference; `get()` / `update()` API; filter helpers `threatsWithinRadius`, `entitiesWithinRadius`, `playersWithinRadius` on the inner `Snapshot` record; `Snapshot.EMPTY` sentinel.
- `fabric-mod/.../cache/WorldStateUpdater.java` — stateless builder; `snapshot(ClientLevel, Player, int)` entry point; package-private helpers `botPosition`, `healthStatus`, `inventorySummary`, `scanEntities`. No `Minecraft.getInstance()` calls; all inputs passed explicitly.
- `fabric-mod/src/test/.../cache/WorldStateCacheTest.java` — 9 tests: sentinel, update/get, three filter helpers, concurrency (100 readers + 1 writer, volatile guarantees zero torn reads).
- `fabric-mod/src/test/.../cache/WorldStateUpdaterTest.java` — 8 tests: `ScannedEntities` record, `Snapshot` round-trips via filter helpers, EMPTY sentinel fields.

## Files Modified

- `FabricModConfig` — added `@Bean WorldStateCache worldStateCache()`.
- `McAgentFabricMod` — added `worldStateCache` and `cacheTickCounter` fields; every-5-tick refresh in `onClientTick()` calling `WorldStateUpdater.snapshot(mc.level, mc.player, 32)`; wires `baritoneBridge.setCache(cache, counters)` in `initSpringContext`; clears both in `shutdownSpringContext`.
- `FabricBaritoneBridge` — `setCache(WorldStateCache, Counters)` setter; nine read methods (`getCurrentPosition`, `getHealthStatus`, `getNearbyThreats`, `getNearbyEntities`, `getNearbyPlayers`, `getInventorySummary`, `getWorldTime`, `isNight`, `getLightLevel`) check cache first, fall back to `ClientThreadExecutor` when empty; `recordCacheHit()` / `recordCacheMiss()` increment `cache.hit` / `cache.miss` counters.
- `AutonomousObserver` — added `WorldStateCache` field; full constructor gains a `worldStateCache` parameter (9th arg); deprecated 3-arg constructor passes `null`; `scan()` calls `threatsFromCacheOrBridge()` and `entitiesFromCacheOrBridge()` helpers that short-circuit to cache when warm.

## Design Decisions

- Cache cleared to `EMPTY` at startup; bootstrap fallback in both bridge and observer means zero regressions during the tick window before first cache population.
- `worldStateCache` and `counters` on `FabricBaritoneBridge` are `volatile` — safe for the Spring thread that calls read methods concurrent with client-thread cache updates.
- `WorldStateUpdater` is Minecraft-typed (`ClientLevel`) because `entitiesForRendering()` only exists on `ClientLevel`, not the abstract `Level`. Called exclusively from the client tick, so the cast is always safe.
- C5's `Supplier<PlayerState>` lambda reads `mc.player` directly on the client tick — unchanged, as per brief (cache not needed there).
- C4's reflex layer can read from `WorldStateCache` directly once implemented.
