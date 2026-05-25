# McAgent Development Logbook

**Format:** Each entry is a dated session summary with what changed, why, and relevant issues/branches.

---

## 2026-05-24 — Session: Expand LLM Capabilities Planning

**Branch:** `feature/expand-llm-capabilities`
**Issues:** #3, #4, #5, #6, #7, #8

### What we did

1. **Branch preparation** — Created `feature/expand-llm-capabilities` from latest `origin/main`.
2. **Baritone integration research** — Dispatched research agent to audit all Baritone APIs against current implementation. Identified 6 major capability gaps:
   - Player / entity scanning (#4)
   - Advanced pathing goals (#5)
   - Safety mode & health monitoring (#6)
   - Inventory queries (#7)
   - Building / placement (#8)
   - Message queuing & harness architecture (#3)
3. **Message queuing analysis** — Audited existing harness threading. Found no formal queue, no deduplication, no priority, no backpressure, and a thread-safety gap where Spring calls Baritone off the main client thread.
4. **GitHub issues created** — Filed 6 enhancement issues in `johnsosoka/McAgent` with full acceptance criteria and interface proposals.

### Files changed / created

- `llm_memory/baritone-integration-research.md` — New. Comprehensive gap analysis.
- `llm_memory/message-queuing-analysis.md` — New. Queue architecture gap analysis + proposed event bus design.
- `llm_memory/logbook.md` — New. This file.

### Decisions made

- **#3 (Message Queuing) is the foundation.** It must be built before or alongside any new Baritone integration to prevent event spam and fix thread safety.
- **Recommended order:** #3 → #4 → #5 → #7 → #6 → #8.
- Each issue will get its own focused branch fetched from the GitHub backlog.

---

---

## 2026-05-24 — Session: Implement Issue #3 — Message Queuing & Event Harness Architecture

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)

### What we did

1. **Created new queue infrastructure in `fabric-mod`**:
   - `ClientThreadExecutor` — dispatches Baritone / Minecraft calls back to the main client thread using `CompletableFuture` + `Minecraft.execute()`. Fixes the thread-safety gap where Spring's background thread was calling Baritone directly.
   - `FrameworkMessageBuffer` — ring buffer (capacity 32) with deduplication, throttling (250ms), and batching for Baritone/game status messages. Prevents spam from flooding `ChatMemory`.
   - `BotEventQueue` — central coordinator with a `PriorityBlockingQueue` for inbound player commands (capacity 8, dedup window 500ms) and a `LinkedBlockingQueue` for outbound chat responses (capacity 4). A dedicated dispatcher thread pulls inbound events and calls `LangChain4jService.processInput()`.
   - `EventPriority` / `InboundEvent` — immutable records for priority-based command scheduling (CANCEL > EMERGENCY > NORMAL).

2. **Updated existing fabric-mod classes**:
   - `FabricChatHandler` — replaced direct `ExecutorService` with `BotEventQueue.enqueueCommand()`. Cancel/stop/halt commands are automatically promoted to `EventPriority.CANCEL`.
   - `FabricBaritoneBridge` — **all** `BotOperations` methods (`navigateTo`, `followPlayer`, `mine`, `cancel`, `pause`, `resume`, `getCurrentPosition`) now wrap their Baritone / Minecraft calls inside `ClientThreadExecutor.execute()`.
   - `McAgentFabricMod` — wires `FrameworkMessageBuffer` → `langChainService.addFrameworkContext()`, `BotEventQueue` → `chatHandler` + outbound drainer, `ChatService` sender → `botEventQueue::enqueueOutbound`.

3. **Verified build & tests**:
   - `:fabric-mod:compileJava` — SUCCESS
   - `:core:compileJava` — SUCCESS
   - `:core:test` — 20 tests PASSED

### Files changed / created

- `fabric-mod/src/main/java/com/mcagent/fabric/queue/ClientThreadExecutor.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/FrameworkMessageBuffer.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/EventPriority.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/InboundEvent.java` — New.
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — Modified (removed executor, wired queue).
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` — Modified (client-thread dispatch for all operations).
- `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` — Modified (queue lifecycle wiring).

### Decisions made

- Kept `BotOperations` interface **unchanged** (synchronous). Thread safety is enforced at the implementation layer via `ClientThreadExecutor`, avoiding cascading changes across `core/` tests and `MinecraftTools`.
- `FabricChatSender` was **not structurally modified** — it remains the final rate-limited, thread-safe transport. The outbound queue in `BotEventQueue` drains into it, satisfying "wired to outbound queue" without duplicating its existing rate-limit logic.
- Memory backpressure (`max-history: 20`) was already present in `application.yml` via `MessageWindowChatMemory`. No config change needed.

### Remaining work on #3

- [x] JAR built and deployed to `~/Library/Application Support/minecraft/mods/`
- [x] Validation guide created at `llm_memory/issue-3-validation-guide.md`
- [ ] Manual test session completed
- [ ] Merge to `main` (pending human approval)

---

---

## 2026-05-24 — Session: Fix .env Loading for Production Launcher

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)

### What we did

**Problem identified from log analysis:**
Bot was unresponsive because the Spring context failed to boot — `FIREWORKS_API_KEY` was not found. The `.env` file existed in the project directory, but the Minecraft launcher runs from `~/Library/Application Support/minecraft/` and never sees it.

**Solution implemented:**
1. Added `java-dotenv` dependency to `core/build.gradle`.
2. Created `EnvLoader` in `core` that tries multiple candidate paths in priority order:
   - Environment variable / system property (already set)
   - Explicit path passed from fabric-mod (e.g. Fabric config directory)
   - Project working directory `./.env` (for dev / Gradle runs)
   - Platform-specific Minecraft config directories:
     - macOS: `~/Library/Application Support/minecraft/config/mc-agent.env`
     - Linux: `~/.minecraft/config/mc-agent.env`
     - Windows: `~/AppData/Roaming/.minecraft/config/mc-agent.env`
3. Wired `EnvLoader.load()` into `McAgentFabricMod.initSpringContext()` before `context.refresh()`, passing `FabricLoader.getInstance().getConfigDir().resolve("mc-agent.env")`.
4. Copied the project's `.env` to `~/Library/Application Support/minecraft/config/mc-agent.env` for production use.

**Files changed / created:**
- `core/build.gradle` — Added `java-dotenv` dependency.
- `core/src/main/java/com/mcagent/core/config/EnvLoader.java` — New. Multi-path .env loader.
- `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` — Modified. Calls `EnvLoader.load()` before Spring context refresh.

**Build & deploy:**
- `:fabric-mod:shadowJar` — SUCCESS
- `:core:test` — 20 tests PASSED
- JAR deployed to `~/Library/Application Support/minecraft/mods/`

---

---

## 2026-05-24 — Session: Fix resolveApiKey to read System Properties

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)

### What we did

**Problem identified from log analysis:**
The `.env` loader was working (`Loaded 19 variables from .env`), but the Spring context still failed with `Fireworks API key not configured`. 

**Root cause:** `LangChain4jConfig.resolveApiKey()` only checked `System.getenv()` (OS environment variables). It did NOT check `System.getProperty()` (Java system properties), which is what `EnvLoader` sets via `System.setProperty()`. When launching from the Minecraft app (not a terminal), there is no OS env var, so the key was invisible.

**Fix:** Updated `resolveApiKey()` to also check `System.getProperty("FIREWORKS_API_KEY")` as a fallback after `System.getenv()`.

**Files changed:**
- `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` — `resolveApiKey()` now checks system properties.

**Build & deploy:**
- `:fabric-mod:shadowJar` — SUCCESS
- JAR redeployed to `~/Library/Application Support/minecraft/mods/`

---

---

## 2026-05-24 — Session: Hardening — NPE Fix, Double Shutdown, Disconnect Debounce

**Branch:** `issue/3-message-queuing`
**Issue:** [#3](https://github.com/johnsosoka/McAgent/issues/3)

### What we did

**Problem identified from log analysis:**
The Spring context successfully booted and the bot was alive, but the user's command `come to -10,143,-79` had **no trigger word** (`bot`/`agent`/`mcagent`/`hey bot`), so it was ignored. Then the context shut down unexpectedly, and Baritone spammed `Pathing was cancelled` into the void, eventually crashing with NPE because the progress callback tried to publish to a null `BotEventQueue`.

**Three bugs fixed:**

1. **Double shutdown** — `FabricChatHandler.shutdown()` called `botEventQueue.shutdown()`, then `McAgentFabricMod.shutdownSpringContext()` called it again. Removed queue shutdown from `FabricChatHandler`; only the mod lifecycle manages it now.

2. **NPE after shutdown** — The Baritone progress callback lambda `msg -> botEventQueue.publishFramework(msg)` captured `botEventQueue` by reference. When shutdown set it to `null`, Baritone's in-flight events crashed.
   - Fixed by adding a null check inside the lambda.
   - Fixed by clearing the callback to a no-op in `shutdownSpringContext()` **before** nulling `baritoneBridge`.

3. **Aggressive disconnect detection** — `mc.getConnection()` briefly returns `null` during dimension changes, respawns, or server lag. The old code shut down immediately.
   - Added a **disconnect debounce counter** (`DISCONNECT_DEBOUNCE_TICKS = 60`, i.e. 3 seconds at 20 TPS).
   - Only calls `shutdownSpringContext()` if the connection stays null for the full debounce period.

**Files changed:**
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — `shutdown()` no longer shuts down the queue.
- `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` — Null-safe callback, disconnect debounce, proper cleanup order.

**Build & deploy:**
- `:fabric-mod:shadowJar` — SUCCESS
- `:core:test` — 20 tests PASSED
- JAR deployed to `~/Library/Application Support/minecraft/mods/`

---

*Next: Restart Minecraft. Use trigger words (`bot`, `agent`, `mcagent`, `hey bot`) in commands. Run the 6 validation checks from `issue-3-validation-guide.md`.*
