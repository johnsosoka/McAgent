# Sprint 12 C3 — Survival/Economy Tools Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-12-survival`
**Build status:** `:core:test` green, `:fabric-mod:test` green, `:fabric-mod:shadowJar` green

---

## Files Changed

### Modified — interface additions
- `core/.../service/ops/ObservationOperations.java` — added `getWorldTime()`, `isNight()`, `getLightLevel()`
- `core/.../service/ops/InventoryOperations.java` — added `pickupNearbyItems(int radius)`
- `core/.../service/ops/BotControlOperations.java` — added `eat(String itemId)`, `placeTorch()`

### Modified — implementation
- `fabric-mod/.../FabricBaritoneBridge.java` — implemented all six new methods
  - `getWorldTime()`: `level.getDefaultClockTime() % 24000`
  - `isNight()`: delegates to `BotControlHelpers.isNightTick()`
  - `getLightLevel()`: `level.getMaxLocalRawBrightness(player.blockPosition())`
  - `pickupNearbyItems()`: AABB query on `ItemEntity` instances, paths to each via Baritone
  - `eat()`: uses `BotControlHelpers` for slot selection + `mc.gameMode.useItem()` for consumption
  - `placeTorch()`: scans adjacent blocks for air+solid surface, places via `mc.gameMode.useItemOn()`

### New — pure-Java helpers
- `fabric-mod/.../BotControlHelpers.java` — `FOOD_PRIORITY` constant, `chooseBestFoodSlot()`, `findItemSlot()`, `isNightTick()`, `qualify()` helpers; package-private, no Minecraft bootstrap needed

### Modified — tools layer
- `core/.../tools/MinecraftTools.java` — six new `@Tool` methods: `eatFood`, `placeTorchAtFeet`, `getWorldTime`, `isNight`, `getLightLevel`, `pickupNearbyItems`

### Modified — test fixtures
- `core/.../tools/MinecraftToolsTest.java` — 14 new mock-based tests covering all six tools
- `core/.../TestRunner.java` — stub implementations for new interface methods in `MockBotOperations`

### New — tests
- `fabric-mod/.../BotControlHelpersTest.java` — 21 pure-Java tests (food ranking, slot selection, night detection, ID qualification)

---

## In-Game Validation Needed

- `eat(null)` / `eat("minecraft:bread")`: confirm food bar rises and the correct item is consumed
- `placeTorch()`: confirm torch appears on the adjacent surface; test at various orientations (wall/floor)
- `getWorldTime()` / `isNight()`: cross-check with F3 debug screen day-time value
- `getLightLevel()`: compare returned value with F3 display at lit and dark locations
- `pickupNearbyItems(16)`: drop several items, call tool, confirm bot paths toward them and vanilla pickup fires on approach

## Implementation Notes

- `eat()` uses `mc.gameMode.useItem()` (high-level API) rather than raw packet construction to avoid sequence-number bookkeeping. Food-level change check is same-tick optimistic; the actual hunger update may arrive the next server tick.
- `placeTorch()` scans N/S/E/W for an air block adjacent to a solid block, then falls back to placing on the block below the player's feet. The torch is selected via `inv.pickSlot()` or `inv.setSelectedSlot()` before `useItemOn()`.
- `getDefaultClockTime()` on `Level` is the correct MC 26.1.2 Mojmap method for the overworld day-cycle position; `getDayTime()` does not exist in this version.
