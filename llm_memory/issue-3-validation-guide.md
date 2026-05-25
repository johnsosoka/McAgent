# Issue #3 Validation Guide — Message Queuing & Event Harness

**Branch:** `issue/3-message-queuing`
**JAR:** `mc-agent-fabric-0.2.0-SNAPSHOT-all.jar`
**Deployed to:** `~/Library/Application Support/minecraft/mods/`

---

## How to Launch for Testing

### Option A: Quick Test via Gradle (Recommended for dev loop)

```bash
./gradlew :fabric-mod:runClient
```

This launches a Minecraft client with the mod + Baritone loaded from the build. Uses a local run directory (not your real `.minecraft`).

**Log location:** `fabric-mod/run/logs/latest.log`

### Option B: Real Minecraft Launcher (Production-like)

The JAR has already been copied to your real `mods/` folder. Launch Minecraft with the Fabric 26.1.2 profile.

**Log location:** `~/Library/Application Support/minecraft/logs/latest.log`

---

## What to Expect at Startup

Open the log and look for these lines in order:

```
[main/INFO] (mc_agent) McAgent Fabric mod initializing...
[main/INFO] (mc_agent) McAgent client setup starting...
[main/INFO] (mc_agent) McAgent initialized successfully. Beans: <number>
[mc-agent-inbound-dispatcher/INFO] (mc_agent) BotEventQueue started. Inbound capacity=8, outbound capacity=4
```

If you see the `BotEventQueue started` line, the queue infrastructure is alive.

---

## Validation Checklist

### 1. Basic Chat Command (Smoke Test)

**Action:** Join a single-player world and type in chat:

```
<yourname> bot what is my location
```

**Expected behavior:**
- Bot responds with coordinates.
- Log shows: `Processing command from <yourname> (priority=NORMAL): what is my location`
- No exceptions in log.

### 2. Cancel Command Priority

**Action:** Start a long navigation, then immediately send:

```
<yourname> bot cancel
```

**Expected behavior:**
- Navigation stops.
- Log shows: `Enqueued command from <yourname> (priority=CANCEL): cancel`
- If you sent multiple commands while pathing, `cancel` should be processed next (not last).

### 3. Framework Message Throttling

**Action:** Send a navigate command to a distant location and watch the log.

```
<yourname> bot go to 1000 64 1000
```

**Expected behavior:**
- While walking, you see Baritone path events.
- Log should NOT show a flood of `<framework>` injections. Instead you see:
  ```
  [mc-agent-framework-buffer/DEBUG] Deduplicated framework message: ...
  ```
  or batched digests like:
  ```
  Status updates: Path calculation complete; now executing | Arrived at destination.
  ```
- Framework messages arrive in chat at most once per 250ms.

### 4. Duplicate Command Deduplication

**Action:** Rapidly spam the same command 5 times in a row:

```
<yourname> bot where are you
<yourname> bot where are you
<yourname> bot where are you
<yourname> bot where are you
<yourname> bot where are you
```

**Expected behavior:**
- Bot responds once or twice, not five times.
- Log shows: `Deduplicated command from <yourname>: where are you`
- Only the first command within the 500ms dedup window is enqueued.

### 5. Queue Backpressure

**Action:** Send 10 different commands rapidly:

```
bot hello 1
bot hello 2
bot hello 3
... (etc)
```

**Expected behavior:**
- Log shows: `Queue full; dropped oldest command from <yourname>: hello 1`
- Bot sends: `I'm busy processing other commands, one moment please.`
- Only 8 commands are retained; the oldest non-priority ones are evicted.

### 6. Thread Safety (No ConcurrentModificationException)

**Action:** While the bot is pathing, send multiple chat commands.

**Expected behavior:**
- No `ConcurrentModificationException` in the log.
- No `IllegalStateException: Client thread execution failed`.
- All Baritone calls happen smoothly.

---

## Known Limitations (Expected)

1. **Outbound queue capacity (4)** means if the LLM tries to send more than 4 rapid messages, some will be dropped. The existing 3-second rate limiter in `FabricChatSender` already prevented this, so this is a defensive boundary.
2. **Framework buffer capacity (32)** means if Baritone emits >32 distinct status messages between throttle windows, the oldest are dropped. This is fine — status is ephemeral.
3. **Client thread timeout (5s)** means if the main Minecraft thread is frozen (e.g., loading chunks), a tool call will throw `IllegalStateException`. This should be rare.

---

## Log Messages to Watch For

| Message | Meaning |
|---------|---------|
| `BotEventQueue started` | ✅ Queue infrastructure is live |
| `Enqueued command ... (priority=...)` | ✅ Command entered the queue |
| `Deduplicated command ...` | ✅ Duplicate suppression working |
| `Queue full; dropped oldest command` | ⚠️ Backpressure triggered (expected under spam) |
| `Deduplicated framework message` | ✅ Framework spam being suppressed |
| `Status updates: ... \| ...` | ✅ Framework messages being batched |
| `Client thread execution failed or timed out` | ❌ Thread dispatch problem — report |
| `Error dispatching inbound event` | ❌ Unexpected crash in dispatcher — report |

---

## If Something Goes Wrong

1. **Bot doesn't respond to chat at all:**
   - Check that `FIREWORKS_API_KEY` is set.
   - Look for `Failed to initialize McAgent Spring context` in the log.
   - Check `latest.log` for Spring bean creation errors.

2. **Bot responds but framework messages are spammy:**
   - Look for `Deduplicated framework message` — if you don't see it, the buffer isn't wired.
   - Check that `McAgentFabricMod` created `frameworkBuffer` and passed it to `BotEventQueue`.

3. **Bot crashes on pathing commands:**
   - Look for `Client thread execution failed` — this means `ClientThreadExecutor` timed out.
   - Check for `ConcurrentModificationException` — old bug that should now be fixed.

---

*Generated for issue/3-message-queuing branch. Update this doc with findings from your test session.*
