# Sprint 13 A5 — Tool Post-Conditions Complete

**Date:** 2026-06-25
**Branch:** `feature/sprint-13-plan-layer`
**Status:** All core tests green. fabric-mod test failures are pre-existing from A6 (AutonomousObserverTest constructor mismatch — unrelated to A5).

## What Was Built

### New package: `core/src/main/java/com/mcagent/core/task/postcondition/`

| File | Description |
|------|-------------|
| `PostCondition.java` | Sealed interface — permits InventoryIncreased, InventoryDecreased, PositionWithin, NoOp |
| `InventoryIncreased.java` | Record: itemId must gain >= minIncrease |
| `InventoryDecreased.java` | Record: itemId must drop >= minDecrease |
| `PositionWithin.java` | Record: position must be within radius of (x,y,z) |
| `NoOp.java` | Sentinel — no verifiable post-condition; framework message suppressed |
| `WorldStateSnapshot.java` | Immutable snapshot: position + sparse inventory counts |
| `PostConditionChecker.java` | Pattern-matched evaluator over all four sealed variants |

### Modified: `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`

- Added static `POST_CONDITIONS` registry (`Map<String, Function<Object[], PostCondition>>`).
- Added `postConditionFor(String toolName, Object... args)` static method for `JobSupervisor` API.
- Injected `LangChain4jService` via `@Setter @Autowired @Lazy` to break the circular Spring dependency (`MinecraftTools → LangChain4jService → Assistant → MinecraftTools`).
- Added `withToolScope(String, PostCondition, Supplier)` overload that: captures a pre-snapshot, executes the action, captures a post-snapshot, evaluates the post-condition, and emits a `<framework>` message via `addFrameworkContext`.
- `NoOp` post-conditions skip the snapshot and emit no framework message.
- Updated `mineResource`, `navigateTo`, `placeBlockAt`, `buildArea`, `placeTorchAtFeet` to call the post-condition overload.

### New Tests (21 test cases across 2 files)

- `PostConditionCheckerTest` — 13 unit tests across all 4 variants (satisfied/unsatisfied/edge cases).
- `PostConditionRegistryTest` — 8 tests verifying `postConditionFor` returns correct types and values for all registered tools including the unknown-tool empty case.
- `MinecraftToolsTest` — 3 new tests: framework message emitted on `mineResource` success (+4 matching), shortage case (+3, "1 short"), and NoOp tool (`followPlayer`) emits no framework message.

## Wiring Note

`JobSupervisor` can now call `MinecraftTools.postConditionFor(toolName, args)` after step completion as specified in the brief. The API is exposed but `JobSupervisor` is not yet rewired to call it (deferred as specified — polish task after A5 lands).

## Files Changed

- `core/src/main/java/com/mcagent/core/task/postcondition/` (6 new files)
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java`
- `core/src/test/java/com/mcagent/core/task/postcondition/PostConditionCheckerTest.java` (new)
- `core/src/test/java/com/mcagent/core/task/postcondition/PostConditionRegistryTest.java` (new)
- `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java`
