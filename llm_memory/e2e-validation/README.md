# McAgent — End-to-End Validation Guides

**Purpose:** Step-by-step in-game test procedures John runs after each sprint lands. Each guide is self-contained: prerequisites, scenarios, expected behavior, pass/fail criteria.

## Test environment

| Item | Value |
|---|---|
| Minecraft version | 26.1.2 (Fabric Loader 0.19.2) |
| World type | Survival, fresh Creative-converted test world recommended (have spawn-protected chest) |
| Mods | `mc-agent-fabric-0.2.0-SNAPSHOT-all.jar` + `baritone-unoptimized-fabric-1.17.0.jar` |
| `.env` | Place `mc-agent.env` with `FIREWORKS_API_KEY=...` in `~/Library/Application Support/minecraft/config/` |
| Test player | Logged in as you; second account optional for multi-player tests |
| Log monitoring | `tail -F ~/Library/Application\ Support/minecraft/logs/latest.log` in a separate terminal |
| Run log (Sprint 11+) | `~/Library/Application Support/minecraft/logs/mc-agent/run-*.jsonl` |

## How to use these guides

1. **Pre-test setup** — install the new mod JAR, restart Minecraft, join the test world.
2. **Walk each scenario top-to-bottom.** Don't skip steps — they often establish state for later checks.
3. **Record outcomes** in the checklist at the bottom of each guide.
4. **Report regressions** by filing a GitHub issue with the scenario number and observed vs. expected.

## Guides

| Sprint | Guide | Focus |
|---|---|---|
| 11 | [sprint-11-validation.md](sprint-11-validation.md) | Hard-stop bypass, status command, instrumentation, memory window, voice |
| 12 | [sprint-12-validation.md](sprint-12-validation.md) | Survival reflexes, death handling, eat/torch tools, no client-thread stalls |
| 13 | [sprint-13-validation.md](sprint-13-validation.md) | Plans, persistence across disconnect, heartbeat, approval gate |
| 14 | [sprint-14-validation.md](sprint-14-validation.md) | Three-mode toggle, named Routines, autonomy, failure recovery |
| 15 | [sprint-15-validation.md](sprint-15-validation.md) | Episodic memory, correction recall, reflection across sessions |

## Common test patterns

### Pattern A — "Hard interrupt" verification
1. Bot is mid-action (mining, building, navigating).
2. Type interrupt command.
3. Measure: time from Enter-key to bot motion ceasing.
4. **Pass:** < 500ms motion-stop on `agent stop`. Chat ack within 1s.

### Pattern B — "Session persistence" verification
1. Bot is mid-multi-step Plan.
2. Disconnect (close Minecraft, kill connection, or `/disconnect`).
3. Reconnect.
4. **Pass:** Bot resumes within 5s; `agent status` shows correct step + progress.

### Pattern C — "Heartbeat cadence" verification
1. Start a long job (≥ 5 minutes expected).
2. Do not interact with bot.
3. **Pass:** Exactly one progress message per minute. No spam between.

### Pattern D — "Reflex latency" verification
1. Bot is mining or idling.
2. Spawn a creeper (`/summon minecraft:creeper ~ ~ ~5`) within reflex radius.
3. **Pass:** Bot reacts (flee / safe mode) within 1s. No LLM round-trip in the log.

## What "validated" means

A scenario is **validated** when:
- Expected behavior reproduced 3 consecutive trials.
- No unexpected log errors (`grep ERROR latest.log`).
- No silent failures (the `mc-agent` JSONL run log shows the events you expect).
- Pass/fail recorded in the per-sprint checklist.

A scenario is **flaky** when 1-2 of 3 trials misbehave — file an issue but do not block the sprint.

A scenario is **failed** when 0-1 of 3 trials succeed — block the sprint until fixed.
