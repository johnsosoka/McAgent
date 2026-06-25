# Sprint 11 Bundle D — Complete

**Date:** 2026-06-25
**Items:** Q6, P1, P2, P3, P4

---

## Files Changed

### core module
- `core/src/main/java/com/mcagent/core/service/Assistant.java`
  - Q6: Removed `<available_tools>` block (33 lines).
  - P1: Added `<voice_guidance>` block before `<output_format>`.

- `core/src/main/java/com/mcagent/core/service/SafetyValidator.java`
  - P3: Replaced stub dangerous-block check (requiresConfirmation) with hard `rejected()`.
  - P3: Added Y-depth check against `maxMiningDepth`. Validate signature now takes `int currentY`.
  - P3: Simplified `ValidationResult` to a 2-field record: `allowed` + `reason`.

- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`
  - P3: Injected `SafetyValidator`. Added guard at head of `mineResource`, `buildArea`, `placeBlockAt`.

### fabric-mod module
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java`
  - P2: Deleted `BLOCK_MAP` static field (13 lines).
  - P2: Refactored `resolveBlock` to return `Optional<Block>` via registry lookup with `minecraft:` prepend and AIR-guard.
  - P2: Updated all four callers (`mine`, `addBlockToAvoid`, `buildPlatform`, `placeBlock`) to handle `Optional`.

- `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java`
  - P4: Replaced `HashSet` with bounded `LinkedHashMap`-backed set (evicts eldest when `size > capacity`).
  - P4: Added package-visible `seenInWindowSize()` helper for tests.

### Tests — new/updated
- `core/src/test/java/com/mcagent/core/service/AssistantSystemPromptTest.java` (new, 2 tests)
  - Q6 regression: asserts `<available_tools>` absent; asserts `<voice_guidance>` present.
- `core/src/test/java/com/mcagent/core/service/SafetyValidatorTest.java` (rewritten, 11 tests)
  - Covers dangerous-block reject, depth-limit reject, exact-limit pass, all non-mine types.
- `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java` (4 new tests)
  - Covers validator reject/approve wiring for `mineResource`, `buildArea`, `placeBlockAt`.
- `fabric-mod/src/test/java/com/mcagent/fabric/queue/FrameworkMessageBufferTest.java` (new, 4 tests)
  - P4: bounded window assertion (capacity+5 publishes → size == capacity), dedup, null/blank guard, flush.

---

## Test Results

```
:core:test     80 tests — all PASSED
:fabric-mod:test  15 tests (11 pre-existing + 4 new) — all PASSED
```

---

## Open Questions / Notes

- `SafetyValidator.validate` signature changed from `(BotAction)` to `(BotAction, int currentY)`. Any future callers outside `MinecraftTools` will need to supply current bot Y; `bot.getCurrentPosition().y()` is the correct source.
- `BUILD` action type in `SafetyValidator` currently passes dangerous-block check (only `MINE` type triggers it). If a future requirement wants to block placing TNT via `buildArea`/`placeBlockAt`, extend the switch case to include `BUILD`.
- P2 `resolveBlock` now uses `Identifier.parse` (throws on malformed input) wrapped in try/catch; malformed strings return `Optional.empty()` with a WARN log.
