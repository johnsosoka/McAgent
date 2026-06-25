# Sprint 15 — End-to-End Validation Guide

**Sprint:** 15 — Memory & Polish
**Build:** `feature/sprint-15-memory` shadowJar
**Estimated total run time:** ~3 days (cross-session memory takes multiple sessions to validate)

## Prerequisites

- [ ] Sprint 14 validated
- [ ] DB preserved across sessions (no clean wipes during test)
- [ ] Stable test world

## Scenario S15.1 — Episodic event logged on every task (Q2) [CRITICAL]

**Goal:** Verify every player command + every plan completion creates an `AgentEvent`.

1. Run 5 distinct commands across 30 minutes: navigate, mine, build, deposit, ask question.
2. Inspect the DB: `select * from agent_event order by created_at desc limit 20;`

**Pass criteria:**
- [ ] Each command has at least one row.
- [ ] Each row has `type` (command/plan_complete/plan_fail/observation), `content` (NL), `importance` (1-10 LLM-scored), `embedding`.
- [ ] Embeddings populated and non-null.

## Scenario S15.2 — Three-factor retrieval surfaces relevant memories (Q2) [HIGH]

**Goal:** Verify retrieval combines relevance, recency, importance.

1. Across past sessions, you've planted memories: "I told the bot my house is at -200, 64, 100" + "I told the bot I don't like building near water" + "I told the bot I prefer oak over birch".
2. Start a new session. Type `agent build me a small house`.
3. Watch the LLM context (JSONL `event="memories_retrieved"`).

**Pass criteria:**
- [ ] The retrieval log includes: the house coordinate memory (high relevance) and the oak preference (relevance) but probably not the "near water" one unless water is implied.
- [ ] Top-3 retrieved memories are reasonable.
- [ ] The plan presented honors the preferences.

## Scenario S15.3 — Correction memory persists across sessions (Q4) [CRITICAL]

**Goal:** Verify corrections survive a restart.

1. Session A: Type `agent place a torch here`. After placement, type `agent that's the wrong spot — never put torches on dirt blocks`. Confirm a correction memory is created (DB row or JSONL `event="correction_saved"`).
2. **Quit Minecraft**.
3. Reopen Minecraft. New session.
4. Type `agent place torches around this area`.

**Pass criteria:**
- [ ] Bot retrieves the correction memory and honors it: no torches placed on dirt.
- [ ] Bot's reply mentions: `Avoiding dirt placement as per your earlier note.`

## Scenario S15.4 — End-of-task reflection (P11) [HIGH]

**Goal:** Verify the bot writes a postmortem on task completion.

1. Complete a plan (any: a 10-block mine job is enough).
2. Inspect JSONL log and DB.

**Pass criteria:**
- [ ] A `event="reflection_written"` event appears in the log.
- [ ] DB row: 2-3 sentence reflection text in `agent_event` with `type="reflection"`, high importance.
- [ ] Example reflection: `Completed mine 10 iron ore in 4 minutes. Smooth — no obstacles. Should consider deeper Y next time for better yield.`

## Scenario S15.5 — Reflection-informed next plan (P11 + Q2) [MEDIUM]

**Goal:** Verify reflections influence the next similar plan.

1. Complete the mine-10-iron task. Reflection mentions "deeper Y for better yield."
2. Type `agent mine 20 iron`. The new plan should consider the prior reflection.

**Pass criteria:**
- [ ] The new plan proposes a deeper Y (e.g., Y=-32 instead of surface).
- [ ] Bot's reply may reference: `Going deeper this time — last run was shallow.`
- [ ] In JSONL log: prior reflection retrieved in `memories_retrieved`.

## Scenario S15.6 — Importance scoring sanity (Q2) [HIGH]

**Goal:** Verify importance scores are reasonable.

1. Inspect the last 20 `agent_event` rows.

**Pass criteria:**
- [ ] Player-issued corrections: importance ≥ 8.
- [ ] Player commands: importance 4-6.
- [ ] Routine observations: importance 1-3.
- [ ] Plan failures: importance 6-8.
- [ ] Plan completions: importance 4-6.

## Scenario S15.7 — Memory decay / pruning (P backlog) [MEDIUM]

**Goal:** Verify the memory store doesn't grow unbounded over many sessions.

This is a longer-running test; check after 1 week of usage:

1. Run several sessions over a week.
2. Inspect `agent_event` count.

**Pass criteria:**
- [ ] Total rows < ~10,000 (depends on session frequency and the agreed retention policy).
- [ ] Low-importance routine observations are pruned (or compressed into summaries).
- [ ] Recent events are retained at full fidelity.

## Scenario S15.8 — HUD panel (P7) — if implemented [LOW]

**Goal:** Verify the optional mod-menu screen.

1. Press default keybind `M`. Mod menu screen opens.
2. Inspect: mode toggle row, current job, progress bar, recent events list, action buttons.
3. Click `STOP JOB`. Bot stops.
4. Click `STATUS TO CHAT`. Chat receives the status string.
5. Press `M` again. Screen closes.

**Pass criteria:**
- [ ] Screen renders correctly.
- [ ] Buttons work without LLM calls.
- [ ] Closing the screen does not pause the bot.

## Scenario S15.9 — Shadow JAR validation in CI [HIGH]

**Goal:** Verify the build-time JAR validator catches unshaded packages.

1. Temporarily add `implementation 'com.example:fake-lib:1.0'` to `fabric-mod/build.gradle` (with a class import) but do not add a relocate.
2. Run `./gradlew :fabric-mod:shadowJar`.

**Pass criteria:**
- [ ] Build fails with a clear message naming the unshaded package.
- [ ] Removing the fake dep makes the build pass again.

## Scenario S15.10 — Cross-session multi-day session (CRITICAL)

**Goal:** Confirm memory + reflection + correction together produce a noticeably "smarter" bot over a week.

1. Day 1: Train the bot with 5 corrections, 3 base waypoints, run 5 plans.
2. Day 2: New session. Issue similar commands; check if corrections / preferences are honored.
3. Day 3: Restart fresh. Verify nothing is lost.
4. Day 7: Type `agent what have you learned about this world`.

**Pass criteria:**
- [ ] Bot provides a coherent summary referencing prior plans, corrections, and preferences.
- [ ] Day 7 behavior demonstrates clear preference learning (oak over birch, base location, etc.).
- [ ] No memory corruption or duplicated rows.

---

## Outcome checklist

| Scenario | Pass / Flaky / Fail | Notes |
|---|---|---|
| S15.1 Event logging | | |
| S15.2 Three-factor retrieval | | |
| S15.3 Correction persistence | | |
| S15.4 Reflection writing | | |
| S15.5 Reflection-informed plan | | |
| S15.6 Importance scoring | | |
| S15.7 Memory pruning | | |
| S15.8 HUD (optional) | | |
| S15.9 Shadow JAR validation | | |
| S15.10 Week-long session | | |

**Sprint 15 status:** ☐ PASS ☐ FAIL
