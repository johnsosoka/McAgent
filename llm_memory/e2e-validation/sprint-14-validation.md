# Sprint 14 — End-to-End Validation Guide

**Sprint:** 14 — Autonomy & Routines
**Build:** `feature/sprint-14-autonomy` shadowJar
**Estimated total run time:** ~3 hours (one long-form autonomy run)

## Prerequisites

- [ ] Sprint 13 validated and merged
- [ ] Fresh test world or trusted survival world
- [ ] Bot pre-seeded with: bread × 32, torches × 32, iron pickaxe, base waypoint set (`agent remember this as base_camp`)
- [ ] Run log + latest.log tailed

## Scenario S14.1 — Three-mode toggle (A1) [CRITICAL]

**Goal:** Verify `agent standby`, `agent watch`, `agent auto` are clean modes.

1. `agent standby`. Reply: `Mode: STANDBY | Observer: off | Job: idle`.
2. `agent watch`. Reply: `Mode: WATCH | Observer: passive | Reflexes: alerts only`.
3. `agent auto`. Reply: `Mode: AUTO | Observer: active | Reflexes: full | Job: idle (will pick goal)`.
4. From `auto`, type `agent standby`. All modes off cleanly.

**Pass criteria:**
- [ ] Each mode toggle replies within 200ms.
- [ ] State persists across `agent status` queries.
- [ ] Legacy aliases (`passive mode`, `active mode`, `watch`) still work but log a deprecation entry.
- [ ] In `standby` mode, observer doesn't fire framework messages.

## Scenario S14.2 — Named Routine: returnHome (A4) [CRITICAL]

**Goal:** Verify a hand-seeded routine works.

1. Bot is ~500 blocks from `base_camp`.
2. Type `agent run returnHome`.

**Pass criteria:**
- [ ] Bot navigates to `base_camp` waypoint.
- [ ] On arrival: `Returned to base_camp.`
- [ ] In JSONL log: `event="routine_invoked" name="returnHome"`.

## Scenario S14.3 — Named Routine: mineUntilFull (A4) [CRITICAL]

**Goal:** Verify a parameterized routine.

1. Type `agent run mineUntilFull with block=cobblestone`.

**Pass criteria:**
- [ ] Plan composed: mine until inventory > 90% full or timeout.
- [ ] Heartbeat reports as expected.
- [ ] On full inventory: `Inventory full at 1856/2048 slots used. Stopping.`

## Scenario S14.4 — LLM-defined routine via `defineRoutine` (A4) [HIGH]

**Goal:** Verify the LLM can author a routine and save it.

1. Type `agent: define a routine called "lighting_check" that walks 10 blocks in each direction and places a torch wherever the light level is below 7. Save it for later.`
2. The LLM should call `defineRoutine(...)` with a parameterized Plan template.
3. Type `agent run lighting_check`.

**Pass criteria:**
- [ ] After the first command: `Routine lighting_check saved.`
- [ ] Second command actually executes the saved routine (deterministically, same way each time).
- [ ] In DB: `routine_definition` row exists with the JSON template.

## Scenario S14.5 — Explain-then-replan on routine failure (Q1) [HIGH]

**Goal:** Verify the DEPS-style explain step.

1. Run a routine guaranteed to fail (e.g. `mineUntilFull` with `block=netherite_ore` in the overworld).
2. Watch the bot's response when the routine errors.

**Pass criteria:**
- [ ] Bot's reply includes a brief NL explanation: `Failed to find netherite_ore — it spawns only in the Nether, deep at Y < 22. I'll need to be in the Nether dimension.`
- [ ] Bot does **not** silently retry forever.
- [ ] In JSONL log: `event="failure_explained"` followed by `event="plan_revised"` (or `event="plan_abandoned"` if no recovery possible).

## Scenario S14.6 — Disambiguation question (Q9) [HIGH]

**Goal:** Verify the LLM asks at most 2 clarifying questions for ambiguous commands.

1. Type `agent build a house`.

**Pass criteria:**
- [ ] Bot replies with one clarifying question: `Where? (here, base, or coordinates) And size? (small 5x5, medium 7x7, or larger)`. Or two atomic questions.
- [ ] Bot does **not** start building.
- [ ] After you answer, bot presents the plan (approval gate from Sprint 13).

## Scenario S14.7 — Per-role model split (P6) [MEDIUM]

**Goal:** Verify the cheaper planner model is used for plan emission.

1. Submit a 5-step plan via `agent`.
2. Inspect `agent stats`.

**Pass criteria:**
- [ ] Counters show separate `llm.planner.calls` and `llm.executor.calls`.
- [ ] Token counts: planner is typically smaller per call than executor.
- [ ] Cost counter (if implemented): planner cost lower than equivalent all-Kimi-K2 baseline.

## Scenario S14.8 — Cost cap halts session (P9) [HIGH]

**Goal:** Verify the cost cap fires.

1. Set `bot.llm.session-cost-cap-usd: 0.05` (deliberately tiny) in `application.yml`.
2. Run a chatty plan that will exceed it (e.g., 20 chat round-trips).

**Pass criteria:**
- [ ] At ~$0.05 spent, bot logs: `Session cost cap $0.05 reached. Halting LLM calls.` and refuses further calls.
- [ ] Tool calls already in flight complete.
- [ ] `agent status` shows the cap state.
- [ ] Player can reset with `agent reset cost`.

## Scenario S14.9 — `agent auto` 3-hour unattended (THE BIG ONE) [CRITICAL]

**Goal:** Verify full autonomy for the product vision.

1. Set up: survival world, base camp with chest (food, torches), bot at base.
2. `agent auto`.
3. Walk away for 3 hours wall-clock (~15 Minecraft days).
4. Return. Type `agent status` and `agent what did you do today`.

**Pass criteria:**
- [ ] Bot is alive.
- [ ] HP > 16, hunger > 12.
- [ ] At least one of: mined resources visible in chest, structure built nearby, inventory contains new resources.
- [ ] No `event="critical_error"` in JSONL log.
- [ ] No more than 3 `event="bot_death"` events.
- [ ] `agent stats`: `llm.calls` < 200 (autonomy should be sparing of LLM calls).
- [ ] Run-log session cost < user-set cap.
- [ ] Bot answers "what did you do today" with a coherent summary (powered by episodic memory if Sprint 15 lands first; otherwise basic stats).

## Scenario S14.10 — Player redirect mid-autonomy [HIGH]

**Goal:** Verify the player can take over a `auto` session.

1. Mid `auto` mode, bot is doing something.
2. Type `agent come here`. Bot should pause autonomy, follow command.
3. Type `agent stop`. Bot stops.
4. Type `agent auto`. Bot resumes autonomy with a fresh decision.

**Pass criteria:**
- [ ] Player command priority > autonomous goals.
- [ ] No state corruption: `agent status` clean across transitions.
- [ ] Autonomy resumes cleanly.

---

## Outcome checklist

| Scenario | Pass / Flaky / Fail | Notes |
|---|---|---|
| S14.1 Three-mode | | |
| S14.2 returnHome | | |
| S14.3 mineUntilFull | | |
| S14.4 defineRoutine | | |
| S14.5 Explain-replan | | |
| S14.6 Disambiguation | | |
| S14.7 Per-role models | | |
| S14.8 Cost cap | | |
| S14.9 3-hour autonomy | | |
| S14.10 Player redirect | | |

**Sprint 14 status:** ☐ PASS ☐ FAIL
