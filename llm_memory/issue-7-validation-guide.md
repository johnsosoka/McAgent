# Issue #7 — Inventory Queries Validation Guide

**Branch:** `issue/7-inventory-queries`  
**Date:** 2026-05-25  
**JAR:** `fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar`

---

## Pre-Test Setup

1. Build and deploy the mod:
```bash
./gradlew :fabric-mod:shadowJar && \
  cp fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar \
  ~/Library/Application\ Support/minecraft/mods/
```

2. Ensure `.env` is at `~/Library/Application\ Support/minecraft/config/mc-agent.env`

3. Launch Minecraft with Fabric 26.1.2 profile

4. Join a world (single-player or server)

---

## Validation Checks

| # | Test | Command | Expected Result |
|---|---|---|---|
| 1 | **Inventory summary** | `agent what do you have in your inventory?` | Bot replies with a summary like `minecraft:cobblestone x64, minecraft:oak_planks x32, ...` |
| 2 | **Check has enough** | `agent do I have 32 cobblestone?` | Bot replies: `You have 64 minecraft:cobblestone (need 32)` |
| 3 | **Check not enough** | `agent do I have 5 diamonds?` | Bot replies: `You only have 2 minecraft:diamond (need 5)` (or similar based on actual inventory) |
| 4 | **Empty inventory** | `agent what's in your inventory?` (on fresh spawn with nothing) | Bot replies: `Inventory is empty.` |
| 5 | **Item ID normalization** | `agent do I have 10 bread?` | Bot should resolve `bread` to `minecraft:bread` and report correctly |
| 6 | **Material verification flow** | `agent can you build a cobblestone platform?` | Bot should use `checkInventory` to verify cobblestone before attempting build |

---

## Log Verification

Good signs in `~/Library/Application\ Support/minecraft/logs/latest.log`:
```
[mc-agent-inbound-dispatcher/INFO]: Enqueued command from <you> (priority=NORMAL): ...
[main/INFO]: Tool: checkInventory(minecraft:cobblestone, 32)
[main/INFO]: Tool: getInventorySummary
```

No errors, no NPEs, no `ConcurrentModificationException`.

---

## Success Criteria

- [ ] `getInventorySummary` returns a readable list of top items
- [ ] `checkInventory` correctly reports has enough / not enough
- [ ] Item IDs without namespace (e.g. `cobblestone`) resolve to `minecraft:cobblestone`
- [ ] Read-only: inventory is NOT modified by these tools
- [ ] Thread-safe: no crashes when inventory queries run alongside pathing

---

## Notes

- These tools scan the bot's own inventory, not the player's.
- `getInventorySummary` is capped at top 10 items to fit in a single chat message.
- Armor and offhand slots are included in the scan.
