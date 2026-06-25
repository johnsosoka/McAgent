# Bundle D — Code Review Follow-up Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-11-foundation`
**Tests:** `./gradlew :core:test :fabric-mod:test` — BUILD SUCCESSFUL

## Fixes Applied

### Fix 1 (CRITICAL) — SafetyValidator BUILD case
- Added `case BUILD -> validateBuild(action)` to the switch in `SafetyValidator.validate()`.
- Implemented `validateBuild(BotAction)`: checks `action.getTarget()` against `dangerousBlocks`; rejects with `"Cannot place <KEYWORD> — listed as dangerous in safety config."`.

### Fix 2 (CRITICAL) — Dead depth check in mineResource
- Removed `.y(currentY)` from the MINE action builder in `MinecraftTools.mineResource`.
- Updated `validateMine` to skip the depth check when `action.getY()` is null or 0 (no target Y provided). Documented via Javadoc that target-Y enforcement is deferred.
- Added `// TODO Sprint 13: pass targetY when MineStep exposes it` at the call site.

### Fix 3 (MAJOR) — FrameworkMessageBuffer dedup race
- Added `seenInWindow.remove(dropped)` immediately after `buffer.pollFirst()` in the eviction branch.
- Added `bufferSize()` and `isSeenInWindow(String)` package-private testing accessors (annotated `@VisibleForTesting` in Javadoc).
- New test `evictedMessageIsRedeliverable` confirms evicted messages can be re-accepted.

### Fix 4 (MINOR) — en-dash in voice_guidance
- Replaced `1-8 words` with `1–8 words` (U+2013) in `Assistant.java`.

### Fix 5 (MINOR) — BUILD dangerous-block tests
- Replaced `validate_build_alwaysAllowed` with four targeted tests in `SafetyValidatorTest`: safe block allowed, TNT rejected, lava rejected, null target allowed.

## Files Changed
- `core/src/main/java/com/mcagent/core/service/SafetyValidator.java`
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`
- `core/src/main/java/com/mcagent/core/service/Assistant.java`
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java`
- `core/src/test/java/com/mcagent/core/service/SafetyValidatorTest.java`
- `fabric-mod/src/test/java/com/mcagent/fabric/queue/FrameworkMessageBufferTest.java`
