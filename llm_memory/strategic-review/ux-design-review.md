# McAgent UX Design Review
**Date:** 2026-06-14
**Reviewer:** UI/UX Design Expert
**Codebase state:** Issue #10 merged — background observation loop complete, all 59 tests passing

---

## Executive Summary

McAgent has strong mechanical foundations: a well-designed three-channel event queue, rate-limited chat output, priority interrupt handling, and a two-mode observation loop. The engineering is solid. The UX, however, is at v0.1 — functional for a developer who built the thing, but not yet trustworthy or legible for a player running 3-hour autonomous sessions.

The five biggest gaps are:

1. **No mode-state awareness for the player.** The bot knows what mode it's in; the player does not. After toggling active mode and walking away, there is no ambient signal that the bot is watching, running, mining, or idle.

2. **The mode model is too granular and expert-facing.** Four independent axes (enabled/disabled, passive/active, individual/summary, plus ad-hoc commands) create a 2×2×2 configuration space. A player asking "go fully automatic" has no single phrase for it.

3. **Long-running jobs are a black box.** Once you say "mine 500 iron ore," you get one confirmation line and then silence until it finishes — or until you happen to type something and notice the bot's passive-mode summary. A 3-hour session has no heartbeat.

4. **Stop/pause/redirect are conflated.** "Stop" cancels everything and drops context. There is no concept of "pause," "hold," or "do this first then resume." The inbound queue's cancel path is all-or-nothing.

5. **Vague commands go straight to the LLM with no clarification gate.** "Build a house" will produce an attempt. Whether that attempt is right depends entirely on how the LLM fills the ambiguity, with no player review before blocks start moving.

None of these are architectural blockers — the existing queue, observer, and tool system can support all of them. This document maps the gaps and provides a phased recommendation path.

---

## Current UX Inventory

### Input surface

| Mechanism | How it works | Gap |
|---|---|---|
| `agent <command>` | Free-text to LLM, any complexity | No disambiguation gate, no complexity limit |
| `bot <command>` | Alias, same path | Trigger word collision risk on servers |
| `agent watch` | Bypass LLM, direct toggle | Terse feedback only: "Observation enabled." |
| `agent active mode` | Bypass LLM, direct toggle | No status readback — player forgets what mode they're in |
| `agent stop` / `agent cancel` | `EventPriority.CANCEL` — drains inbound queue | Drops in-flight commands; no pause semantics |
| (nothing) | No keybind, no HUD, no GUI | — |

### Output surface

| Channel | Rate limit | Gap |
|---|---|---|
| LLM conversational reply | 500ms per message, cap 4 queued | Long multi-message responses can queue behind each other |
| Tool immediate confirmation | Same outbound queue | "Following player John" before LLM latency — good |
| Observation alert (passive) | Framework buffer, sits in LLM memory | Player sees it only when they next say something to the bot |
| Observation alert (active) | Urgent dispatcher, immediate | Potentially noisy if hostile mobs are plentiful |

### State the bot tracks, the player does not

- Observer enabled/disabled
- Observer mode (passive / active)
- Message mode (individual / summary)
- Current Baritone goal (navigation, mining, exploration, follow)
- Safety mode state
- Block avoidance list
- Pathing behavior mode
- Short-term memory (last 20 messages)
- Location and note memories (persistent)

The player has zero ambient visibility into any of this after the first confirmation message scrolls off chat.

### Toggle phrase surface area

Current working phrases for observer toggles (in `FabricChatHandler`):
- `watch`, `start watching`, `observe`
- `stop watching`, `stop observing`, `disable observation`
- `passive mode`, `set passive`
- `active mode`, `set active`

These bypass the LLM. Nothing else does. All other commands (including stop/cancel/halt) go through the LLM with `EventPriority.CANCEL`, which means they still wait for LLM round-trip — a problem if the LLM is mid-call.

---

## Player Journey Maps

### Journey 1 — First-Time Autonomy ("I want the bot to just go farm iron for a few hours")

```
Player intent: delegate a long task, walk away, check back later

Step 1: Player types "agent mine iron ore 500 radius 128"
        Bot: "Mining IRON_ORE (up to 500 blocks within 128 radius)"
        [Player has no idea if bot found any, started walking, needs a pick, etc.]

Step 2: Player walks away. 30 min pass.

Step 3: Player returns, types something random to test.
        Bot: "I've mined 47 iron ore so far. There's a zombie 12 blocks north."
        [Player discovers the update was sitting in memory the whole time — passive mode]

Step 4: Bot reaches inventory capacity. Silently stops. No alert.
        [Player discovers this only when they type again, or never]

Step 5: Player types "agent stop"
        Priority CANCEL clears inbound queue.
        Bot eventually: "Cancelled current operation."
        [Did it actually stop Baritone? Is the bot idle or still walking?]

Step 6: Player wants to start again with better settings.
        Types "agent active mode"
        Bot: "Observation mode set to active. I'll act on threats automatically."
        [Player still doesn't know if mining is resumed or bot is just watching now]
```

**Pain points:** No progress visibility, no completion/failure signals, ambiguous "stop" semantics, mode state invisible after confirmation scrolls.

---

### Journey 2 — Mid-Job Interruption ("I need the bot to come here right now")

```
Player intent: interrupt a long mining job, get bot's attention, issue urgent command

Step 1: Bot is 400 blocks away, strip mining at Y=-50.

Step 2: Player under attack. Types "agent come here"
        Message enters inbound queue at NORMAL priority.
        [Bot is mid-LLM-call from a previous framework observation. Player command waits.]

Step 3: Player types "agent stop"
        CANCEL priority. Clears inbound queue but "come here" was already there
        and now gets dropped. Bot stops. Doesn't come to player.
        [Player must retype "come here" after stop completes]

Step 4: Player types "come here" again.
        Bot processes, calls followPlayer.
        "Following player John"
        [45-90 seconds for bot to walk from -50 strip mine to surface to player]

Step 5: Player is dead.

Alternative flow — if bot is in active mode and sees the threat:
        Urgent dispatcher fires. Bot sends "Hostile mob detected! Enabling safe mode."
        Bot enables safety mode for ITSELF but doesn't come to the player.
        Player still under attack.
```

**Pain points:** "Stop then redirect" requires two commands. "Come immediately" is not meaningfully faster than a normal command due to queue ordering. Active mode observation protects the bot, not the player.

---

### Journey 3 — Long-Running Task with Checkpoints ("Build a starter base, stock it, report when done")

```
Player intent: issue a high-complexity autonomous task, trust the bot to execute, get summary

Step 1: Player types "agent build a starter base and stock it with some food"
        [LLM receives this. No disambiguation. Interprets freely.]
        Bot responds with a plan (multi_step_guidance prompt kicks in).
        "I'll build a 5x5 oak platform, walls, and roof, then mine food."
        [Player sees the plan. No approval step. Bot immediately starts.]

Step 2: Bot begins buildArea calls. 
        Each placeBlockAt sends a chat message: "Placed minecraft:oak_planks at (X, Y, Z)"
        [For a 5x5x3 structure: 75+ individual confirmations. Outbound queue saturates.
         Chat floods. Server may start rate-limit warnings.]

Step 3: Bot runs out of oak planks mid-build.
        "Not enough minecraft:oak_planks. Need 12, have 3."
        Bot stops. Does not attempt alternative (cobblestone, etc.)
        Does not prompt player for next step.
        Long silence.

Step 4: Player returns 20 minutes later, sees the partial build, sees the inventory message.
        Must manually continue. Has to re-read back through chat to understand current state.

Step 5: Player types "agent status"
        LLM has no structured status tool. Interprets freely, may call getStatusReport()
        which returns health/hunger/threats but NOT current goal or job progress.
```

**Pain points:** No pre-execution approval for complex plans. Build confirmations create chat flood. No graceful "I can't proceed, waiting for instructions" signal. No structured status query. Job progress is inaccessible after the initial chat scrolls away.

---

## Gap Analysis

### G1 — No persistent mode status signal
The player has no way to check current mode without the bot telling them. After a session restart or a distraction, there's no way to know: is observation on? Is it active or passive? What is the bot doing right now?

**Root cause:** All state lives in `AutonomousObserver` and `BotProperties`. Nothing exposes it back to the player without a specific query.

**Impact:** High. Leads to duplicate commands, accidental re-triggering, false safety assumptions.

---

### G2 — Mode model is 4-axis; users think in 3 modes
Users mentally model autonomous agents in three modes:
- **Off** — bot is present but not doing anything
- **Watch** — bot monitors and alerts, takes defensive action, never acts proactively
- **Go** — bot is working a job or acting fully autonomously

The current 4-axis model (enabled × passive/active × individual/summary × ad-hoc commands) maps poorly onto this. Players don't think "I want passive individual observation with ad-hoc commands on the side."

**Root cause:** The axes evolved from implementation concerns, not user mental models. Each axis was added as a feature, not composed into a coherent mode.

**Impact:** Medium for power users, high for new players. Trust is hard to build when you can't easily describe what mode the bot is in.

---

### G3 — No progress heartbeat for long jobs
Jobs longer than ~2 minutes have no ambient output. The 500ms outbound drain rate, outbound cap of 4, and passive-mode observation (where framework messages only surface on player input) all conspire to keep the bot silent during long autonomous operations.

**Root cause:** Intentional rate limiting (correct for kick avoidance) without a complementary "periodic status" design.

**Impact:** High for the primary product vision of 3-hour sessions. Players either forget the bot is running or don't know when it finishes.

---

### G4 — Interrupt semantics are incomplete
`EventPriority.CANCEL` clears the inbound queue but not the current Baritone goal — that depends on the LLM deciding to call `cancelCurrentOperation()`. True "stop everything right now" requires the LLM to respond. "Pause and do this instead" doesn't exist at all.

**Root cause:** The priority system was designed for queue ordering, not for multi-level interruption semantics (pause vs. cancel vs. redirect).

**Impact:** High when it matters most — urgent situations. The bot's most critical UX (stop!) is also its most architecturally uncertain.

---

### G5 — Disambiguation gap for complex commands
"Build a house" goes to the LLM with the multi-step guidance prompt, which tells it to sendMessage about what it's about to do before acting. This is better than nothing — the player gets a plan preview. But the bot does not wait for approval before executing. If the plan is wrong (wrong location, wrong materials, wrong size), the player must stop the bot mid-execution and undo damage manually.

**Root cause:** `multi_step_guidance` in the system prompt covers "announce then act" but not "announce, await approval, then act."

**Impact:** Medium now (build primitives are limited), High as autonomous capability expands.

---

### G6 — No failure/correction memory
When the bot does something wrong ("it built the floor in the wrong spot"), the player can say "stop, that's wrong." The bot stops. But this correction is not memorized. Next session, same command, same wrong behavior. There is no "remember that I told you X was wrong" mechanism tied to corrections.

**Root cause:** `rememberNote` exists but is never called autonomously on corrections. The correction is lost when chat memory rolls over (max 20 messages).

**Impact:** Medium. Compounds over time — the bot keeps making the same mistakes.

---

### G7 — Build output is too chatty
`placeBlockAt` sends a chat confirmation for every single block. `buildArea` for a 5×5×3 structure is 75 confirmations. The outbound queue cap of 4 means most of these are silently dropped. What the player does receive is a meaningless stream of coordinates.

**Root cause:** Tool-level feedback was designed for single-action confirmation (correct) but is applied uniformly to high-volume batch operations (incorrect).

**Impact:** Medium. Makes builds feel noisy and cheap. More importantly, it masks the actual useful signals (errors, completion).

---

### G8 — No status query tool
There is no `getAgentStatus()` tool that returns: current goal, mode, observation state, job progress. `getStatusReport()` covers health/hunger/threats — not agent state. Players have no single command to get a coherent answer to "what are you doing right now?"

**Root cause:** Status tools were added incrementally per feature. No unified status surface was designed.

**Impact:** Medium, but high for trust. Trust requires legibility.

---

## Recommendations

### MVP (Next Sprint — Immediate Trust Foundations)

#### R1 — Unified Three-Mode Model
Collapse the 4-axis configuration into 3 named modes with a single toggle command and single status indicator:

```
agent standby    →  observer disabled, no autonomous action
agent watch      →  observer enabled (passive), alerts surface on player input, flee-on-threat enabled  
agent auto       →  observer enabled (active), bot acts on threats/opportunities, executes queued jobs
```

The existing `passive mode` / `active mode` toggle commands become **aliases** for `watch` and `auto`. `individual` vs `summary` message mode becomes an internal default (summary at auto, individual at watch) rather than a player-facing axis.

Toggle responses must echo current full state, not just the change:
```
[McAgent] Switched to AUTO mode. Observer: active | Job: mining IRON_ORE (47/500)
```

This requires a `getAgentStatus()` method and a persistent mode enum — small additions.

---

#### R2 — Hard-Stop Bypass (Direct Baritone Cancel)
`cancel`/`stop`/`halt` detected in `FabricChatHandler.resolvePriority` should bypass the LLM entirely and directly call `bot.cancel()`, the same pattern as observer toggles. Current behavior (CANCEL priority still routes to LLM) adds latency to the most critical user action.

Implementation: in `handleToggleCommand`, add a stop clause:
```java
if (lower.equals("stop") || lower.equals("cancel") || lower.equals("halt") || lower.equals("abort")) {
    bot.cancel();               // direct Baritone cancel, no LLM round-trip
    FabricChatSender.send("Stopped.");
    botEventQueue.clearInbound();   // drain remaining commands
    return true;
}
```

This requires exposing `clearInbound()` on `BotEventQueue` and injecting `BotOperations` into `FabricChatHandler` — straightforward.

---

#### R3 — Job Heartbeat (Periodic Status Message)
For any job running longer than a configurable threshold (default: 60 seconds), the `AutonomousObserver.scan()` tick should also emit a brief progress message to the player via the outbound queue. Frequency: once per minute maximum, suppressed if the player has interacted in the last 30 seconds (to avoid nagging during active sessions).

Example output format:
```
[McAgent] Mining update: 83/500 iron ore | Y=-52 | Inventory 60% full
[McAgent] Mining update: 201/500 iron ore | Y=-52 | Inventory 78% full — getting full
[McAgent] Inventory full. Stopped at 312 iron ore. Come collect or say "agent drop off and continue".
```

This requires: a job-progress tracking structure in `LangChain4jService` or a new `JobTracker` component, and a heartbeat emission path in `AutonomousObserver`. The job-progress data should come from tool completion events (already logged to framework buffer) rather than polling.

---

#### R4 — Status Query Command (Direct Bypass)
Add `agent status` as a bypass command in `FabricChatHandler.handleToggleCommand` that immediately sends a composed status string without LLM round-trip:

```java
if (lower.equals("status") || lower.equals("what are you doing")) {
    String status = buildStatusString();   // reads observer state + current job from JobTracker
    FabricChatSender.send(status);
    return true;
}
```

Example output:
```
[McAgent] Mode: AUTO | Observer: active | Job: mining IRON_ORE (201/500) | Health: 18/20 | Safe mode: OFF
```

This is the single most-requested feature for an autonomous agent — "what are you doing right now?" — and it should never require LLM latency to answer.

---

#### R5 — Batch Build Silence (No Per-Block Chat)
Remove `chatService.send(...)` from `placeBlockAt` in `MinecraftTools`. Replace with a single completion message after `buildArea` finishes, and an error message only on failure:

```
[McAgent] Building oak_planks platform 5x1x5... done. (25 blocks placed)
[McAgent] Build error: ran out of oak_planks at block 23 of 25.
```

The intermediate per-block confirmations currently going to outbound queue can be replaced by framework-buffer messages (invisible to player, LLM-visible for context) so the LLM can still track progress without spamming chat.

---

### Phase 2 (Autonomy Trust Layer)

#### R6 — Pre-Execution Approval Gate for Complex Commands
Add a `confirm` flag to the `multi_step_guidance` system prompt section: when estimated duration exceeds a threshold (e.g., more than 3 tool calls planned, or build volume > 50 blocks), the bot presents its plan and waits for player approval:

```
[McAgent] Plan for "build a starter base":
  1. Build 7x7 oak platform at current location (Y=64)
  2. Build 3-high walls around perimeter
  3. Add oak door at south face
  4. Mine and collect 2 wheat for food starter
  Estimated time: ~8 minutes. Reply "agent go" to start or "agent change X" to adjust.
```

Implementation: this is a system prompt change + a new `PENDING_APPROVAL` state in the inbound dispatcher. The dispatcher holds the pending plan until the player approves, rejects, or modifies. The "agent go" command is another bypass.

---

#### R7 — Pause and Resume Semantics
Add "pause" as a distinct mode from "stop":

```
agent pause        →  suspends current Baritone goal, remembers it, bot stays in place
agent resume       →  resumes suspended goal from where it stopped
agent stop         →  cancels goal entirely, forgets it
```

Implementation: `JobTracker` stores the current job descriptor (tool name + parameters + progress). Pause calls `bot.cancel()` but preserves the descriptor. Resume re-invokes the tool with adjusted parameters (e.g., remaining count). This requires tools like `mineResource` to accept an offset parameter, or the tracker to issue a fresh command with remaining count.

---

#### R8 — Correction Memory
When the player issues a correction ("that's wrong, stop" / "don't build there" / "not that block type"), the LLM should automatically call `rememberNote(playerName, correction, "correction")` before cancelling. This note is retrieved on next session start via `recallNotes`. Add a system prompt section:

```
<correction_guidance>
When the player says something is wrong, incorrect, or expresses dissatisfaction with an action,
call rememberNote(playerName, "Player correction: [their words]", "correction,memory")
before stopping. Always acknowledge what you've noted.
</correction_guidance>
```

---

#### R9 — Structured Disambiguation for Ambiguous Commands
Add to the system prompt a disambiguation tier: before acting on commands with greater than N estimated tool calls, classify confidence:

```
<disambiguation_guidance>
Before starting a multi-step task, assess ambiguity:
- HIGH confidence (clear location, clear materials, clear scope): proceed with plan preview
- MEDIUM confidence (location unclear, or materials unspecified): ask 1 clarifying question
- LOW confidence (intent unclear, conflicting constraints): ask up to 2 questions, do not start
Never ask more than 2 clarifying questions. If still unclear, propose the most conservative interpretation and ask for approval.
</disambiguation_guidance>
```

Example:
```
Player: "agent build a house"
Bot: "Where should I build it? (near you, at base camp, or give coordinates)"
Player: "near me"
Bot: "Plan: 7x5 oak cabin, 3 blocks from your position. Reply 'agent go' to start."
```

---

#### R10 — "Come to me" as a Hard-Bypass Command
Add "come here" / "come to me" / "follow me" to the bypass list in `FabricChatHandler`, resolving player position from `Minecraft.getInstance().player` and directly calling `bot.navigateTo(playerX, playerY, playerZ)` without LLM round-trip. This is the most common urgent spatial command and should never wait for the LLM.

---

### Stretch Goals (HUD / GUI / Personality)

#### R11 — Mod Menu Status Panel (GUI)

A Fabric mod-menu screen (triggered by keybind, default `M`) showing current agent state. This is a stretch because it requires a Mixin-free screen class and a Fabric API screen registration, but the data already exists.

ASCII mockup:

```
+-----------------------------------------------+
|  McAgent                             [v0.2.0]  |
+-----------------------------------------------+
|  Mode:   [  STANDBY  ] [  WATCH  ] [  AUTO  ] |
|                                                |
|  Current Job                                   |
|  Mining IRON_ORE — 201 / 500 blocks            |
|  [ == == == == = . . . . . . . . . . ]  40%    |
|  Running 34 min | Y=-52 | Bot HP: 18/20        |
|                                                |
|  Observer                                      |
|  Enabled | Threat radius: 32 | Scan: 1s        |
|  Last scan: no new threats                     |
|                                                |
|  Recent Events                                 |
|  > Mined 10x IRON_ORE (4 min ago)             |
|  > Zombie detected 28 blocks NE (6 min ago)   |
|  > Inventory 80% full (8 min ago)             |
|                                                |
|  [  STOP JOB  ]  [ PAUSE ]  [ STATUS TO CHAT ]|
+-----------------------------------------------+
```

This screen is read-only with three action buttons. Mode can be toggled via the radio row at top. It does not need to issue LLM commands — it talks directly to `AutonomousObserver` and `JobTracker`.

Keybind `M` opens/closes. Screen closes on Escape or clicking outside.

---

#### R12 — In-World Job Marker

When a long job is active (mining, building, exploring), place a rendered beacon-style marker at the bot's current target coordinates, visible as an in-world waypoint. Requires Fabric rendering hooks (no Mixin required — `WorldRenderEvents` API).

The marker fades when the job completes. Color codes:
- Blue: navigating
- Yellow: mining/building
- Green: following player
- Red: threat response / fleeing

---

#### R13 — Voice and Personality Refinement

Current system prompt personality: "McAgent, a helpful Minecraft assistant." This is neutral and forgettable. For a bot you leave running for 3 hours, personality matters for trust.

Recommended voice: **Competent, terse, self-aware of its own limitations.** Not chatty. Not apologetic. Not playful. Like a capable field agent reporting status.

Compare:

| Current | Recommended |
|---|---|
| "I'll be careful." | "Safe mode on." |
| "I'm starting to navigate to..." | "Heading to (100, 64, 200)." |
| "Sorry, something went wrong. Check the logs." | "Failed — check console. Last position: (X, Y, Z)." |
| "Observation mode set to passive." | "Watching. Alerts on your next message." |
| "Safe mode disabled. Normal behavior restored." | "Safe mode off." |

The bot should be brief in success, precise in failure, and never waste words on filler. Length signals: short command = short response. Long autonomous task = structured update, not a paragraph.

System prompt addition:
```
<voice_guidance>
Voice: terse, competent, direct. Like a field agent reporting to command.
- Confirmations: 1-8 words. "Done." "Mining started." "Safe mode on."
- Progress: structured. "Mining: 83/500 | Inv: 60% full"
- Errors: specific. "Out of oak_planks (need 12, have 3). Stopped."
- Questions: one at a time. Never more than two.
- Personality: professional warmth, no filler words, no apologies for normal operation.
</voice_guidance>
```

---

#### R14 — Job Queue (Future)

Phase 3 concept: the ability to queue multiple jobs that execute sequentially:

```
agent queue: mine 200 iron, then go to base, then craft tools, then mine 200 coal
```

The `BotEventQueue` inbound infrastructure already supports ordering. The addition is a `JobQueue` service that holds a list of job descriptors and advances to the next on completion. Each job can have an `onComplete` handoff that triggers the next. The player can inspect the queue ("agent queue status") and modify it ("agent remove step 3").

This is the natural endpoint of the 3-hour autonomous session vision.

---

## Summary Priority Matrix

| Recommendation | Impact | Effort | Phase |
|---|---|---|---|
| R2 — Hard-stop bypass | Critical | Low | MVP |
| R4 — Status query bypass | High | Low | MVP |
| R1 — Unified 3-mode model | High | Medium | MVP |
| R5 — Batch build silence | High | Low | MVP |
| R3 — Job heartbeat | High | Medium | MVP |
| R8 — Correction memory | Medium | Low | Phase 2 |
| R6 — Pre-execution approval gate | High | Medium | Phase 2 |
| R10 — "Come to me" bypass | High | Low | Phase 2 |
| R7 — Pause/resume semantics | Medium | Medium | Phase 2 |
| R9 — Structured disambiguation | Medium | Medium | Phase 2 |
| R13 — Voice/personality | Medium | Low | Stretch |
| R11 — Mod menu status panel | High | High | Stretch |
| R12 — In-world job marker | Medium | High | Stretch |
| R14 — Job queue | High | High | Stretch |

---

## Closing Note

McAgent is, mechanically, one of the more thoughtful LLM-game integrations I've seen: the priority queue, framework buffer throttling, and urgent dispatcher all show care for the constraints of this environment. The work to make it a bot you'd actually trust with 3 hours of your world is primarily a design problem, not an engineering one. The single highest-leverage change is R2 (hard-stop bypass) because trust in an autonomous agent is built on confidence that you can always interrupt it cleanly. The second is R4 (status query bypass), because legibility is the foundation of trust. Everything else builds on those two.
