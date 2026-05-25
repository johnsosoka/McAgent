# Issue #7 — Inventory Queries Implementation Brief

**Branch:** `issue/7-inventory-queries`  
**Date:** 2026-05-25  
**Status:** BotOperations interface scaffolded. Ready for implementation.

---

## What Changed (Scaffold)

`BotOperations.java` now has 3 new methods:

```java
boolean hasItem(String itemId, int count);
int countItem(String itemId);
String getInventorySummary();
```

---

## What Needs Implementation

### 1. `FabricBaritoneBridge.java` (`fabric-mod/`)

Implement all 3 methods inside `ClientThreadExecutor.execute()` blocks.

**Minecraft inventory access pattern (Mojmap 26.1.2):**
```java
Player player = Minecraft.getInstance().player;
Inventory inv = player.getInventory();
for (ItemStack stack : inv.items) {
    if (!stack.isEmpty()) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); // e.g. "minecraft:cobblestone"
        int count = stack.getCount();
    }
}
```

**`hasItem(String itemId, int count)`:**
- Normalize itemId: if it doesn't contain `:` prefix with `minecraft:`.
- Scan `inv.items`, sum counts matching the resolved ID.
- Return `sum >= count`.

**`countItem(String itemId)`:**
- Same normalization and scan logic.
- Return total count.

**`getInventorySummary()`:**
- Scan `inv.items` + `inv.armor` + `inv.offhand`.
- Build a map of `itemId → totalCount`.
- Sort by count descending.
- Return a truncated string listing top ~10 items: `"cobblestone x64, oak_planks x32, ..."`
- If inventory is empty, return `"Inventory is empty."`
- Keep the string short enough to fit in a single Minecraft chat message.

### 2. `MinecraftTools.java` (`core/src/.../tools/`)

Add 2 `@Tool` methods (these are what the LLM sees):

```java
@Tool("Check if the bot has at least a given count of an item in inventory. Use item IDs like minecraft:cobblestone, minecraft:diamond, minecraft:bread.")
public String checkInventory(
        @P("Item ID, e.g. minecraft:cobblestone") String itemId,
        @P("Minimum count needed") int count) { ... }

@Tool("Get a summary of the bot's inventory contents")
public String getInventorySummary() { ... }
```

**Behavior:**
- `checkInventory`: call `bot.hasItem(itemId, count)`. Return `"You have X <itemId> (need Y)"` or `"You only have X <itemId> (need Y)"`.
- `getInventorySummary`: call `bot.getInventorySummary()`. Return the summary directly.
- Use `chatService.send()` for immediate feedback on `checkInventory` (following the pattern of `navigateToSurface`, `goToDepth`, etc.).
- Log with `log.info("Tool: ...")`.

### 3. `Assistant.java` (`core/src/.../service/`)

Update the `@SystemMessage`:

- Add 2 new tools to `<available_tools>`:
  - `checkInventory(itemId, count)` — check if bot has enough of an item
  - `getInventorySummary()` — list inventory contents

- Add new `<inventory_guidance>` section:
  - `checkInventory` uses item IDs like `minecraft:cobblestone`, `minecraft:diamond`, `minecraft:bread`.
  - `getInventorySummary` gives a quick overview; use `checkInventory` for specific material verification.
  - These tools are read-only — they do NOT modify inventory.

### 4. `TestRunner.java` (`core/src/test/.../`)

Add mock implementations to `MockBotOperations`:

```java
@Override
public boolean hasItem(String itemId, int count) {
    log.info("[MOCK] hasItem({}, {})", itemId, count);
    // Return true for common items, false for others
    return List.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:bread")
            .contains(itemId.toLowerCase());
}

@Override
public int countItem(String itemId) {
    log.info("[MOCK] countItem({})", itemId);
    return List.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:bread")
            .contains(itemId.toLowerCase()) ? 64 : 0;
}

@Override
public String getInventorySummary() {
    return "cobblestone x64, oak_planks x32, bread x16, iron_pickaxe x1";
}
```

### 5. `MinecraftToolsTest.java` (`core/src/test/.../tools/`)

Add unit tests using Mockito:

```java
@Test
void checkInventory_shouldReturnHasEnough_whenHasItem() {
    when(bot.hasItem("minecraft:cobblestone", 32)).thenReturn(true);
    when(bot.countItem("minecraft:cobblestone")).thenReturn(64);

    String result = tools.checkInventory("minecraft:cobblestone", 32);

    assertThat(result).contains("64 minecraft:cobblestone");
    verify(bot).hasItem("minecraft:cobblestone", 32);
}

@Test
void checkInventory_shouldReturnNotEnough_whenMissing() {
    when(bot.hasItem("minecraft:diamond", 5)).thenReturn(false);
    when(bot.countItem("minecraft:diamond")).thenReturn(2);

    String result = tools.checkInventory("minecraft:diamond", 5);

    assertThat(result).contains("2 minecraft:diamond");
    verify(bot).hasItem("minecraft:diamond", 5);
}

@Test
void getInventorySummary_shouldReturnBotSummary() {
    when(bot.getInventorySummary()).thenReturn("cobblestone x64, oak_planks x32");

    String result = tools.getInventorySummary();

    assertThat(result).isEqualTo("cobblestone x64, oak_planks x32");
    verify(bot).getInventorySummary();
}
```

---

## Build Verification

After all changes:
```bash
./gradlew :core:compileJava
./gradlew :core:test
./gradlew :fabric-mod:compileJava
```

All must pass before declaring done.

---

## Reference Files

- `core/src/main/java/com/mcagent/core/service/BotOperations.java` — interface (already scaffolded)
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — implementation target
- `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` — @Tool target
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — prompt target
- `core/src/test/java/com/mcagent/core/TestRunner.java` — mock target
- `core/src/test/java/com/mcagent/core/tools/MinecraftToolsTest.java` — test target
