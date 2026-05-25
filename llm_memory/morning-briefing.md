# Morning Briefing — McAgent Issue #3 Testing

**Date:** 2026-05-25 (morning session)
**Branch:** `issue/3-message-queuing` (local commit saved)
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)
**JAR:** `mc-agent-fabric-0.2.0-SNAPSHOT-all.jar` already deployed to `~/Library/Application Support/minecraft/mods/`
**Config:** `~/Library/Application Support/minecraft/config/mc-agent.env` (copied from project `.env`)

---

## Quick Start

1. **Launch Minecraft** with the Fabric 26.1.2 profile (mod is already in `mods/`)
2. **Join a world/server**
3. **Use trigger words** in chat commands:
   - `bot what is my location`
   - `agent come here`
   - `bot go to 100 64 200`
   - `agent cancel`
   - `mcagent remember this as home`
   - `hey bot find diamonds`

**Without trigger words, the bot ignores you by design.**

---

## What We Built (Issue #3)

### New infrastructure
- **Message queuing** — player commands go through a priority queue (cancel > normal)
- **Framework throttling** — Baritone status messages are deduplicated and batched before hitting the LLM
- **Thread safety** — all Baritone/Minecraft calls dispatch back to the main client thread
- **.env loading** — API keys load from `~/Library/Application Support/minecraft/config/mc-agent.env`

### Bugs fixed during this session
- API key not found when launching from Minecraft app (not terminal)
- NPE crash when Baritone events fired after shutdown
- Double shutdown of event queue
- Aggressive disconnect detection triggering during dimension changes

---

## 6 Validation Checks

| # | Test | Command | What to look for in log |
|---|---|---|---|
| 1 | **Basic response** | `bot what is my location` | `Enqueued command ... (priority=NORMAL)` + bot replies in chat |
| 2 | **Cancel priority** | Start pathing, then `bot cancel` | `Enqueued ... (priority=CANCEL)` — should preempt queued commands |
| 3 | **Framework throttling** | `bot go to 1000 64 1000` (long walk) | Batched status like `Status updates: Path calc complete \| Arrived` |
| 4 | **Deduplication** | Spam `bot where are you` 5× fast | Only 1-2 responses; log shows `Deduplicated command` |
| 5 | **Queue backpressure** | Send 10 different commands fast | `Queue full; dropped oldest command` + `I'm busy, one moment` |
| 6 | **Thread safety** | Path + chat simultaneously | No `ConcurrentModificationException` or `Client thread execution failed` |

**Log location:** `~/Library/Application Support/minecraft/logs/latest.log`

---

## Success Indicators

Good signs in the log:
```
[Render thread/INFO]: Loading .env from .../config/mc-agent.env
[Render thread/INFO]: Loaded 19 variables from .env
[Render thread/INFO]: McAgent initialized successfully. Beans: 45
[Render thread/INFO]: BotEventQueue started. Inbound capacity=8, outbound capacity=4
[mc-agent-inbound-dispatcher/INFO]: Enqueued command from <you> (priority=NORMAL): ...
```

---

## Failure Indicators

If you see these, report them:
```
[Render thread/ERROR]: Failed to initialize McAgent Spring context
... Fireworks API key not configured ...
```
→ `.env` file missing from `config/` directory

```
java.lang.NullPointerException: Cannot invoke "BotEventQueue.publishFramework"
```
→ Old JAR still loaded. Verify you have the latest build.

```
java.util.ConcurrentModificationException
```
→ Thread safety regression. Report immediately.

---

## Full Details

- `llm_memory/issue-3-validation-guide.md` — step-by-step test procedures
- `llm_memory/logbook.md` — complete session history with all changes
- `llm_memory/message-queuing-analysis.md` — architecture design doc
- `llm_memory/baritone-integration-research.md` — next issues (#4-#8) ready to go

---

## Next Issue (when #3 is done)

**#4 — Player / Entity Scanning**
`locatePlayer`, `scanForPlayers`, `scanForMobs` — enables the bot to report coordinates, distance, and direction to any loaded player or mob without blindly following them.

Branch will be: `issue/4-player-scanning`

---

*Ready to test. Launch Minecraft and say `bot hello`.*
