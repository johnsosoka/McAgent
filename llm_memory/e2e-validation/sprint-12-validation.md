# Sprint 12 — End-to-End Validation Guide

**Sprint:** 12 — Bot Stays Alive
**Build:** `feature/sprint-12-survival` shadowJar
**Estimated total run time:** ~90 minutes (includes an overnight unattended scenario)

## Prerequisites

- [ ] Sprint 11 validated and merged
- [ ] New jar built, copied, Minecraft restarted
- [ ] Test world: survival, daytime start, food + torches available in chest at spawn
- [ ] `tail -F` on `latest.log` and JSONL run log running

## Scenario S12.1 — `BotOperations` interface split is invisible to players (C2) [HIGH]

**Goal:** Verify the role-interface refactor didn't break any tool.

1. Run all Sprint 11 scenarios S11.9 (tool catalogue). All must still pass.

**Pass criteria:**
- [ ] No tool returns "interface not found" or similar.
- [ ] `./gradlew test` green.

## Scenario S12.2 — Eat tool works (C3) [CRITICAL]

**Goal:** Verify `eat` consumes food and restores hunger.

1. Use `/effect give @s minecraft:hunger 60 5` to drop hunger to ~10/20.
2. Drop a stack of bread in the bot's inventory: `/give @p minecraft:bread 64`. Pick up.
3. Type `agent eat bread`. Bot should consume bread.
4. Type `agent eat` (no arg). Bot picks best available food.

**Pass criteria:**
- [ ] Food bar visibly rises.
- [ ] Bread count drops in bot inventory.
- [ ] Bot reply: short ack like `Ate bread. Hunger: 16/20.`
- [ ] In JSONL log: `event="tool_call" tool="eat"` with success.

## Scenario S12.3 — Auto-eat reflex (C4) [CRITICAL]

**Goal:** Verify the reflex layer eats without LLM involvement when hunger drops.

1. Set `bot.observation.mode: auto` (or use Sprint 14's `agent auto`).
2. Bot has bread in inventory.
3. Apply hunger effect: `/effect give @s minecraft:hunger 120 10` repeatedly until bot hunger is ~6/20.
4. Watch the bot.

**Pass criteria:**
- [ ] Within 5 seconds of hunger crossing the threshold (default 8), the bot eats.
- [ ] No `event="llm_call"` in JSONL log between threshold crossing and eat.
- [ ] `event="reflex_action" action="eat"` appears in JSONL log.
- [ ] Bot does **not** announce eating in chat (reflex actions are silent by default).

## Scenario S12.4 — Auto-flee reflex (C4) [CRITICAL]

**Goal:** Verify the reflex layer flees at low health.

1. Autonomous mode active.
2. Apply damage to bot's health (`/effect give @s minecraft:instant_damage 1 1` carefully — drop bot to ~6/20 HP).
3. Spawn a hostile mob 8 blocks from bot.

**Pass criteria:**
- [ ] Within 2 seconds, bot enables safe mode and flees toward a safer waypoint.
- [ ] No LLM call between threat detection and flee.
- [ ] `event="reflex_action" action="flee"` in log.

## Scenario S12.5 — Torch placement at night (C3 + C4) [HIGH]

**Goal:** Verify the bot places torches in dark areas.

1. Bot has torches in inventory.
2. `/time set night`.
3. Bot is standing in an unlit area (light level < 7).
4. Active mode on.

**Pass criteria:**
- [ ] Within 30 seconds, bot places a torch nearby.
- [ ] Or, if a `placeTorch` tool is called explicitly by the LLM during night, it executes.
- [ ] `event="tool_call" tool="placeTorch"` in log.

## Scenario S12.6 — Death and respawn (C5) [CRITICAL]

**Goal:** Verify the death handler records, suspends, and reports.

1. Bot is mid-task (e.g., navigating to coords).
2. Kill the bot: `/kill @e[name=McAgent]` or stand it in lava.
3. Wait for respawn.
4. Type `agent status`.

**Pass criteria:**
- [ ] In JSONL log: `event="bot_death" location="..."` entry.
- [ ] In framework buffer: a `<framework>You died at (X,Y,Z) — task X suspended</framework>` is logged.
- [ ] After respawn, `agent status` reports `Last death: (X,Y,Z) 1m ago | Job: paused`.
- [ ] No NPE in log.
- [ ] Bot does **not** spam chat with death messages.

## Scenario S12.7 — Day/night awareness tools (C3) [HIGH]

**Goal:** Verify `getWorldTime` and `isNight` work.

1. `/time set day`. Type `agent what time is it`. Bot answers: day, around dawn/noon/dusk based on time.
2. `/time set night`. Type `agent is it dark out`. Bot answers yes.

**Pass criteria:**
- [ ] Both queries return correct values.
- [ ] Bot reply is structured: `Day. Time: 8000 (noon-ish).`

## Scenario S12.8 — Client thread not blocked under read load (Q7) [HIGH]

**Goal:** Verify the read-only state cache prevents tool-chain timeouts.

1. Spawn ~100 entities near the bot: `/execute as @p run particle minecraft:explosion ~ ~ ~ 0 0 0 0 100`... actually use `/summon minecraft:pig` 100 times via macro.
2. Type 5 commands within 10 seconds: `agent scan entities`, `agent what's nearby`, `agent who's around`, `agent threats`, `agent inventory`.
3. Monitor framerate. Watch the JSONL log for any `event="client_thread_timeout"`.

**Pass criteria:**
- [ ] No `client_thread_timeout` in log.
- [ ] Framerate doesn't drop below 30 FPS.
- [ ] All 5 replies land within ~10 seconds.
- [ ] In JSONL log: `event="state_cache_hit"` entries appear (cached reads).

## Scenario S12.9 — Unattended overnight survival (CRITICAL — INTEGRATION TEST)

**Goal:** Confirm the bot survives 1 in-game day without dying or starving.

1. Daytime start.
2. Bot in a small fenced area with: 1 chest of food, 16 torches, open access to a 2-deep hole as auto-shelter.
3. `agent auto` (if Sprint 14 also landed) or `agent active mode` + ensure reflexes enabled.
4. Walk away (player AFK 10 minutes wall-clock = ~5 Minecraft days).
5. Return.

**Pass criteria:**
- [ ] Bot alive.
- [ ] Bot's hunger > 12.
- [ ] Bot's HP > 18.
- [ ] No more than 2 `bot_death` events in log (bot can die once and recover).
- [ ] At least one `reflex_action="eat"` and one `reflex_action="flee"` event in log.
- [ ] `agent stats` shows reflex action counts > 0.

---

## Outcome checklist

| Scenario | Pass / Flaky / Fail | Notes |
|---|---|---|
| S12.1 Interface split | | |
| S12.2 Eat tool | | |
| S12.3 Auto-eat reflex | | |
| S12.4 Auto-flee reflex | | |
| S12.5 Torch placement | | |
| S12.6 Death/respawn | | |
| S12.7 Day/night | | |
| S12.8 Read cache | | |
| S12.9 Overnight survival | | |

**Sprint 12 status:** ☐ PASS ☐ FAIL
