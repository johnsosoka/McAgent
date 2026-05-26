# Issue #8 — Building / Placement Validation Guide

**Branch:** `issue/8-building-placement`  
**Date:** 2026-05-26  
**JAR:** `mc-agent-fabric-0.2.0-SNAPSHOT-all.jar` (deployed to mods folder)

---

## Pre-Test Setup

1. Build and deploy the mod (already done):
```bash
./gradlew :fabric-mod:shadowJar && \
  cp fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar \
  ~/Library/Application\ Support/minecraft/mods/
```

2. Ensure `.env` is at `~/Library/Application\ Support/minecraft/config/mc-agent.env`

3. Launch Minecraft with Fabric 26.1.2 profile

4. Join a world (single-player recommended for first build test)

5. **Gather materials** — Put at least 25 oak planks and 1 torch in your bot's inventory

---

## Validation Checks

| # | Test | Command | Expected Result |
|---|---|---|---|
| 1 | **Build platform (sufficient materials)** | `agent build a 5x5 oak plank platform at my feet` | Bot verifies materials, replies "Building minecraft:oak_planks area 5x1x5", and places blocks |
| 2 | **Build platform (insufficient materials)** | `agent build a 20x20 oak plank platform` (with only 10 planks) | Bot replies "Not enough minecraft:oak_planks. Need 400 blocks, only have 10" and does NOT start building |
| 3 | **Place single block** | `agent place a torch at 100 65 100` | Bot verifies has torch, replies "Placed minecraft:torch at (100, 65, 100)" |
| 4 | **Place block (missing material)** | `agent place a diamond block at my feet` (with no diamond blocks) | Bot replies "No minecraft:diamond_block in inventory to place" |
| 5 | **Multi-step structure** | `agent build me a small house` | LLM should chain multiple `buildArea` calls (floor → walls → roof) and report progress via `sendMessage` |
| 6 | **Material verification accuracy** | `agent build a 3x3x3 cobblestone cube` | Volume = 27. Bot checks for 27 cobblestone. If has 26, should report "Need 27, have 26" |

---

## Log Verification

Good signs in `~/Library/Application\ Support/minecraft/logs/latest.log`:
```
[mc-agent-inbound-dispatcher/INFO]: Tool: buildArea(0, 64, 0, 4, 64, 4, minecraft:oak_planks)
[main/INFO]: Tool: placeBlockAt(100, 65, 100, minecraft:torch)
[main/INFO]: Baritone: buildPlatform(0, 64, 0, 4, 64, 4, minecraft:oak_planks)
[main/INFO]: Baritone: placeBlock(100, 65, 100, minecraft:torch)
```

No errors, no NPEs.

---

## Success Criteria

- [ ] `buildArea` constructs a filled rectangular area when materials are sufficient
- [ ] `buildArea` refuses to build and reports count when materials are insufficient
- [ ] `placeBlockAt` places a single block when material is available
- [ ] `placeBlockAt` refuses when material is missing
- [ ] Volume calculation is correct: `width = abs(x2-x1) + 1` (inclusive)
- [ ] `allowPlace` is automatically enabled (Baritone can place blocks)
- [ ] LLM can chain multiple build calls for complex structures
- [ ] Thread-safe: no crashes when building alongside other operations

---

## Safety Notes

- Start with small builds (5x5 or smaller) to verify behavior
- In multiplayer, be mindful of where you build — the bot places blocks in the world
- The bot does NOT auto-gather materials; it only warns if short
- Large builds may take significant time; the bot will path to each placement location

---

## Troubleshooting

**"Cannot build: Unknown block type"**
→ The block type didn't resolve. Try using full IDs like `minecraft:oak_planks`.

**"No minecraft:oak_planks in inventory"**
→ The item ID may need the `minecraft:` prefix. Or you genuinely don't have the item.

**Bot doesn't place blocks**
→ Check that `allowPlace` is being set (should be automatic). Verify Baritone builder process is active.

---

## Next Steps After Validation

- Mark in-game validation as PASSED in logbook
- Merge PR #14 to main
- Evaluate whether LLM spatial reasoning is sufficient, or if a `DesignService` is needed
