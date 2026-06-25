# Sprint 11 — End-to-End Validation Guide

**Sprint:** 11 — Operations & Trust Foundation
**Build:** `feature/sprint-11-foundation` shadowJar
**Estimated total run time:** ~60 minutes

## Prerequisites

- [ ] New `mc-agent-fabric-0.2.0-SNAPSHOT-all.jar` copied to mods folder
- [ ] Old jar removed; only one McAgent jar present
- [ ] `mc-agent.env` contains `FIREWORKS_API_KEY`
- [ ] Minecraft restarted
- [ ] Test world loaded (survival creative-flat world recommended)
- [ ] `tail -F ~/Library/Application\ Support/minecraft/logs/latest.log` running in terminal A
- [ ] `ls -lt ~/Library/Application\ Support/minecraft/logs/mc-agent/run-*.jsonl | head -1` then `tail -F` that file in terminal B

## Scenario S11.1 — Hard-stop latency (F3) [CRITICAL]

**Goal:** Verify `agent stop` halts the bot in < 500ms without an LLM round-trip.

1. Type `agent navigate to 500 -50 500`. Bot replies "Navigating to (500, -50, 500)" and begins moving.
2. Wait ~5 seconds (bot is walking).
3. Type `agent stop`. **Start stopwatch on Enter key.**
4. Watch the bot model. Stop stopwatch when the bot stops moving.

**Pass criteria:**
- [ ] Stopwatch reads < 500ms (subjective: instantaneous).
- [ ] Bot replies `Stopped.` within 1s.
- [ ] In log: no `LangChain4jService` call entry for the stop. Only `FabricChatHandler` toggle.
- [ ] In JSONL run log: `event="hard_stop"` entry.

**Try also:** `agent cancel`, `agent halt`, `agent abort` — all should behave identically.

## Scenario S11.2 — Status command instant (F3) [CRITICAL]

**Goal:** Verify `agent status` returns in < 100ms without an LLM call.

1. Bot in idle state (no current job). Type `agent status`. Expect: `Mode: WATCH | Observer: passive | Job: idle | HP: 20/20 | Inv: 0%` (or similar).
2. Type `agent mine iron ore 10`. Wait until "Mining started" reply.
3. Immediately type `agent status`. Expect: job line shows mining IRON_ORE with elapsed time.
4. Wait 2 minutes. Type `agent status` again. Expect updated elapsed time + a count if Baritone has reported progress.

**Pass criteria:**
- [ ] Each `agent status` reply lands in chat within 1s (visibly instant).
- [ ] In log: no `LangChain4jService.processInput` entry for status calls.
- [ ] Job description updates correctly across the 3 status calls.

## Scenario S11.3 — Outbound chat not silently dropped (F2) [CRITICAL]

**Goal:** Verify the chat-drop bug is fixed. The bot's LLM-generated messages should land in chat, not be silently swallowed.

1. Type `agent come here`. Wait until bot acks.
2. Type three commands in rapid succession (within 5s): `agent what is your position`, `agent what is in your inventory`, `agent what is my position`.
3. The bot will queue replies. Wait 15 seconds.

**Pass criteria:**
- [ ] All three replies land in chat (may take 4–8 seconds total due to 1.2s pacing).
- [ ] In JSONL run log: zero `event="outbound_dropped"` entries during the test.
- [ ] `counters` snapshot via `agent stats` shows `outbound.rate_limited` > 0 (expected — re-queued, not dropped) and `outbound.dropped` == 0.

## Scenario S11.4 — Memory window does not evict mid-task intent (F1) [HIGH]

**Goal:** Verify the new tiered chat memory preserves the player's original intent through a stream of framework messages.

1. With active observer mode: `agent active mode`. Then `agent enable observation` if not already (toggle commands).
2. Move close to several entities — spawn 5 cows, 5 chickens, 2 zombies (in creative for setup, then `/gamemode survival`).
3. Type `agent come here and bring me a name for the closest cow`. (A multi-step request that requires holding the intent across observation chatter.)
4. The autonomous observer will inject ~30+ framework messages over the next 60 seconds.
5. After the bot arrives, see if it still answers the question about naming the cow.

**Pass criteria:**
- [ ] Bot answers the cow-naming question in its reply.
- [ ] `agent status` between steps shows the right job.
- [ ] In JSONL log: `event="memory_summary"` entries appear (summaries should have rolled in).
- [ ] No "what was I doing again?" type response from the LLM.

## Scenario S11.5 — Urgent observation does not race or double-inject (F1 + Q5) [HIGH]

**Goal:** Verify `processUrgentObservation` is atomic and free of double injection.

1. `agent active mode`. Stand in an open area.
2. From another terminal or in-game console, prepare a creeper spawn at distance 10: `/summon minecraft:creeper ~ ~ ~10`.
3. Just before spawning, send a normal command: `agent tell me about your day`.
4. **Within 100ms**, summon the creeper.
5. The inbound LLM call and the urgent observation will race.

**Pass criteria:**
- [ ] Both replies land coherently — one to the player's chat, one to the threat.
- [ ] No truncated or interleaved chat output.
- [ ] In JSONL log: each `event="llm_call"` has a `cmd_id`; no `cmd_id` appears twice with conflicting payloads.
- [ ] Bot reacts to the creeper within 2s (urgent dispatcher works).

## Scenario S11.6 — Voice is terse (P1) [MEDIUM]

**Goal:** Verify the LLM responds in short, structured sentences.

1. Type each of these in turn; record the bot's response length:
   - `agent come here` — should reply 1–6 words.
   - `agent what is your health` — short structured `HP: 20/20`.
   - `agent enable safe mode` — should reply `Safe mode on.` or similar.
   - `agent navigate to 100 64 100` — short ack.

**Pass criteria:**
- [ ] All replies under 12 words.
- [ ] No "I'll do my best to..." or "Let me try to..." filler.
- [ ] Structured progress format observed at least once.

## Scenario S11.7 — Counters and run log (F4) [HIGH]

**Goal:** Verify operational instrumentation lands real data.

1. Run a 5-minute mixed session: 3 navigates, 1 mine, 1 build, several observer events.
2. After the session, type `agent stats`.

**Pass criteria:**
- [ ] Reply contains the expected counter names: `inbound.enqueued`, `inbound.dispatched`, `inbound.deduped`, `inbound.dropped`, `outbound.sent`, `outbound.rate_limited`, `framework.published`, `framework.dropped`, `llm.calls`, `llm.errors`.
- [ ] Counters are non-zero and proportional (e.g., `llm.calls` ≈ count of commands you sent).
- [ ] In JSONL log: each command has a `cmd_id` that threads through `inbound_enqueued` → `inbound_dispatched` → `llm_call` → `tool_call` (one or more) → `outbound_sent`.
- [ ] You can grep the log for one `cmd_id` and reconstruct the full request lifecycle.

## Scenario S11.8 — Watchdog fires on simulated stall (F5) [MEDIUM]

**Goal:** Verify the watchdog detects a stuck dispatcher.

This is hard to test without contrived load. The unit test should already cover correctness. For e2e, simulate via rapid command burst:

1. Type 10 commands within 10 seconds: `agent come here`, `agent go to 100 64 100`, `agent go to 200 64 200`, `agent mine iron`, etc.
2. The inbound queue should backpressure (capacity 8). Some commands will be deduped or dropped.
3. While the LLM works through the backlog, monitor for any chat message of the form `WARN: dispatcher stalled` — should **not** appear in a healthy run.

**Pass criteria:**
- [ ] No watchdog alert during normal burst (false positive).
- [ ] `agent stats` shows `inbound.dropped` > 0 (queue backpressure correctly dropping overflow).

(Genuine deadlock testing happens in unit tests — `WatchdogTest`.)

## Scenario S11.9 — Tool catalogue auto-discovery (Q6) [HIGH]

**Goal:** Verify the LLM still calls tools correctly after removing the manual catalogue.

1. Issue each tool category once:
   - Navigation: `agent come here`
   - Mining: `agent mine 4 iron ore`
   - Inventory: `agent what do you have`
   - Building: `agent place a torch here`
   - Player: `agent where am I`
   - Memory: `agent remember this as test_spot_a`

**Pass criteria:**
- [ ] Each command triggers the expected tool call (visible in JSONL log: `event="tool_call"`).
- [ ] No "I don't have a tool to do that" responses.
- [ ] No regressions from previous baseline.

## Scenario S11.10 — Hygiene (P2, P3, P4) [LOW]

**Goal:** Spot-check the small fixes.

1. (P2) Type `agent mine some quartz_block`. Expect: bot recognizes the block via registry; either mines or replies "no quartz_block in range." Should not reply "unknown block."
2. (P3) Type `agent mine 4 TNT`. Expect: `SafetyValidator` rejects: `Cannot mine TNT — listed as dangerous.`
3. (P4) Run the bot for 30+ minutes with active observation. Use `jconsole` or watch heap in logs. The `FrameworkMessageBuffer.seenInWindow` set should not grow unbounded — heap stable.

**Pass criteria:**
- [ ] P2: registry-based block resolution works.
- [ ] P3: TNT mining rejected with the validator message; no Baritone activity.
- [ ] P4: stable heap during long-running observation.

---

## Outcome checklist

| Scenario | Pass / Flaky / Fail | Notes |
|---|---|---|
| S11.1 Hard-stop | | |
| S11.2 Status | | |
| S11.3 Outbound | | |
| S11.4 Memory window | | |
| S11.5 Urgent race | | |
| S11.6 Voice | | |
| S11.7 Counters | | |
| S11.8 Watchdog | | |
| S11.9 Tool catalogue | | |
| S11.10 Hygiene | | |

**Sprint 11 status:** ☐ PASS ☐ FAIL — sign and date when complete.

---

## Triage cheatsheet

| Symptom | Likely cause | First diagnostic |
|---|---|---|
| Hard-stop takes > 1s | Bypass not wired in `FabricChatHandler` | `grep "handleToggleCommand" latest.log` to see if stop hit the bypass |
| Status shows wrong job | `JobTracker.setCurrentJob` not called from tool method | Add `JobTracker.set(...)` to the offending tool |
| Chat replies missing | `drainOutbound` polled instead of peeked on rate-limit | Check counters: `outbound.rate_limited` should grow without `outbound.dropped` |
| Memory still evicts intent | `LayeredChatMemory.pin` not set | `application.yml`: confirm `bot.chat.working-window` ≥ 12 |
| Counter not incrementing | Spring bean not autowired into the call site | Check the bean shows in `springContext.getBeanDefinitionCount()` log line |
