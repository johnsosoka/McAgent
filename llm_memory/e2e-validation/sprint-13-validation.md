# Sprint 13 — End-to-End Validation Guide

**Sprint:** 13 — The Plan Layer
**Build:** `feature/sprint-13-plan-layer` shadowJar
**Estimated total run time:** ~75 minutes

## Prerequisites

- [ ] Sprint 12 validated and merged
- [ ] Fresh DB recommended (delete `data/mc_agent_db.*` to start clean)
- [ ] Test world: survival, near base camp with chest of materials
- [ ] Tail logs and JSONL run log

## Scenario S13.1 — Plan submission via natural language (C1, A2) [CRITICAL]

**Goal:** Verify the LLM emits a structured Plan and the executor receives it.

1. Type `agent mine 64 iron ore`.
2. The Planner role should decompose; the Assistant should call `submitPlan(...)` with a typed Plan.

**Pass criteria:**
- [ ] Bot reply summarizes the plan: `Plan: mine IRON_ORE (target 64) at current Y. Approval needed.` (if approval gate active) or `Plan accepted. Starting.`
- [ ] In JSONL log: `event="plan_submitted"` with `task_id=<UUID>`, `steps=[...]`.
- [ ] `agent status` shows: `Job: plan <id> | Step 1/1 (MineStep IRON_ORE 0/64) | 12s elapsed`.

## Scenario S13.2 — Plan persists across disconnect (C1) [CRITICAL]

**Goal:** Verify `SessionState` saves the active Plan and restores it on reconnect.

1. Start a 5-minute plan: `agent navigate to 500 64 500, then mine 16 cobblestone`.
2. Wait 30 seconds. `agent status` shows step 1 in progress.
3. **Close Minecraft entirely** (force-quit).
4. Wait 60 seconds.
5. Reopen Minecraft. Join the same world.
6. Wait for Spring context init.
7. Type `agent status`.

**Pass criteria:**
- [ ] On reconnect chat: `Resumed task <id> at step 1/2 after 1m disconnect.` (or framework message visible to LLM).
- [ ] `agent status` shows the same task ID, same step, advanced or restarting cleanly.
- [ ] Bot resumes navigating (or mining if step 1 was complete).
- [ ] In JSONL log: `event="session_resumed" task_id=<id>`.

## Scenario S13.3 — Heartbeat fires once per minute (A3) [HIGH]

**Goal:** Verify long-job heartbeat is exactly 1/minute and silent when player active.

1. Start a 5+ minute mining job.
2. Walk away — no commands for 5 minutes.
3. Watch chat.

**Pass criteria:**
- [ ] Exactly 4–5 progress messages over 5 minutes (one per minute).
- [ ] Format: structured. Example: `Mining: 47/200 IRON_ORE | Y=-32 | Inv 36%`.
- [ ] After 5 minutes idle, type `agent come here` — bot acks immediately.
- [ ] Within 30 seconds of player activity, heartbeat suppression activates: no auto-progress message during that window.

## Scenario S13.4 — Pre-execution approval for complex plans (Q3) [HIGH]

**Goal:** Verify the approval gate blocks complex plans.

1. Type `agent build me a 7x7 oak cabin here with a door and a window`.
2. The bot should preview the plan and ask for approval before executing.

**Pass criteria:**
- [ ] Bot reply: structured plan preview (steps numbered, estimated time, materials check).
- [ ] Bot does **not** begin building yet.
- [ ] Type `agent go` (or `agent approved`). Building starts.
- [ ] Alternative: type `agent change wall height to 4`. Bot should re-present (best-effort).

## Scenario S13.5 — Step failure escalates (Q1 — DEPS-style) [HIGH]

**Goal:** Verify the executor escalates step failure to the LLM with structured context.

1. Type `agent mine 64 diamonds at this depth`. (Likely no diamonds at surface depth.)
2. The MineStep will time out or exhaust without progress.

**Pass criteria:**
- [ ] Within `step.timeout`, the executor flags failure.
- [ ] Bot calls back to the LLM with a synthetic prompt that includes structured context: `<framework>Step X failed: NO_BLOCKS_FOUND in radius 64</framework>`.
- [ ] LLM responds in the bot's voice: `Mining iron at surface depth — no diamonds here. Try mining at Y=-32?`
- [ ] In JSONL log: `event="plan_step_failed"` followed by `event="failure_escalated"`.

## Scenario S13.6 — Cancel mid-plan (C1 + F3) [CRITICAL]

**Goal:** Verify `agent stop` cleanly cancels the active Plan.

1. Mid-plan (any step running).
2. Type `agent stop`.

**Pass criteria:**
- [ ] Bot motion stops < 500ms.
- [ ] `agent status` reports `Job: idle`.
- [ ] In JSONL log: `event="plan_cancelled"`.
- [ ] No NPEs after cancel.

## Scenario S13.7 — Task-aware observer suppression (A6) [MEDIUM]

**Goal:** Verify the observer doesn't interrupt safe long tasks for distant threats.

1. Active observer mode. Long mining plan running.
2. Spawn a creeper 30 blocks away from the bot.

**Pass criteria:**
- [ ] Observer detects the creeper but does **not** invoke an LLM urgent observation.
- [ ] Mining continues.
- [ ] If you spawn a creeper 8 blocks away, the observer **does** interrupt: safety threshold honored.
- [ ] In JSONL log: `event="observation_suppressed" reason="task_low_risk"`.

## Scenario S13.8 — Three-mode `agent status` includes plan progress [HIGH]

**Goal:** Verify `agent status` integrates with `JobTracker` through the new Plan layer.

1. With a plan running, type `agent status`.

**Pass criteria:**
- [ ] Reply includes plan summary, step index, step progress (e.g. `47/64 iron`), wall-clock elapsed, ETA if computable.
- [ ] Reply returns < 100ms.

## Scenario S13.9 — Tool post-conditions surface in framework messages (A5) [HIGH]

**Goal:** Verify post-conditions are checked and reported.

1. Type `agent mine 4 cobblestone`.
2. Watch the JSONL log for the framework message after the mine.

**Pass criteria:**
- [ ] Each tool call's framework message includes `expected_postcondition` and `actual_state_delta`.
- [ ] Example: `mineResource → expected: +4 cobblestone; observed: +4 cobblestone` or `+3 cobblestone (1 dropped: out of range)`.
- [ ] The Plan executor uses this for step completion check, not just the Baritone "operation complete" event.

---

## Outcome checklist

| Scenario | Pass / Flaky / Fail | Notes |
|---|---|---|
| S13.1 Plan submission | | |
| S13.2 Persist across disconnect | | |
| S13.3 Heartbeat cadence | | |
| S13.4 Approval gate | | |
| S13.5 Failure escalation | | |
| S13.6 Cancel mid-plan | | |
| S13.7 Task-aware observer | | |
| S13.8 Status w/ plan | | |
| S13.9 Post-conditions | | |

**Sprint 13 status:** ☐ PASS ☐ FAIL
