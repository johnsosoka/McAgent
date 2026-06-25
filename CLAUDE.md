# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

McAgent is an LLM-driven autonomous Minecraft agent — a Fabric **26.1.2** client mod that bridges natural-language chat commands to in-game actions. Player chat → LangChain4j (Fireworks.ai) → tool calls → Baritone pathfinding.

## Build, Test, Run

```bash
# Full build + tests (core on Java 21, fabric-mod on Java 25)
./gradlew build

# Build just one module
./gradlew :core:build
./gradlew :fabric-mod:build

# Shadow JAR (the actual mod artifact, with relocated deps)
./gradlew :fabric-mod:shadowJar
# Output: fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar

# Run a single test class / method
./gradlew :core:test --tests com.mcagent.core.tools.MinecraftToolsTest
./gradlew :core:test --tests 'com.mcagent.core.service.LangChain4jServiceTest.shouldInjectPlayerContext'
./gradlew :fabric-mod:test --tests com.mcagent.fabric.observer.AutonomousObserverTest
```

**JDK requirements (both must be installed):**
- Java **21** — used to compile and test `core/` (Spring Boot 3.4 baseline)
- Java **25** — used to compile and test `fabric-mod/` (matches Minecraft 26.1.2's target)
- `gradle.properties` pins the Java 21 install path via `org.gradle.java.installations.paths` (homebrew `openjdk@21`); update this if your local path differs.

**Install for in-game testing:**
```bash
# After ./gradlew :fabric-mod:shadowJar
cp fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar \
   ~/Library/Application\ Support/minecraft/mods/
# Baritone is a separate mod and must be installed alongside:
cp .local-deps/baritone-unoptimized-fabric-1.17.0.jar \
   ~/Library/Application\ Support/minecraft/mods/
```

**Required env var:** `FIREWORKS_API_KEY` — read from `.env` at startup. Inside Minecraft, the mod also looks for `<minecraft-config>/mc-agent.env` (see `EnvLoader` invocation in `McAgentFabricMod.initSpringContext`).

## Architecture

### Two-module bridge

```
core/         Pure Java 21, no Minecraft dependencies.
              Spring Boot + LangChain4j + JPA + vector store.
              Defines `BotOperations` interface — the abstraction over the game world.

fabric-mod/   Fabric 26.1.2 mod (Java 25, Unimined+Mixin).
              Depends on :core. Provides `FabricBaritoneBridge` (the BotOperations impl
              that talks to Baritone), Mixins, the event queue, and the autonomous observer.
```

`core/` must remain Minecraft-free so it stays unit-testable on plain JVM. Any Minecraft/Baritone type belongs in `fabric-mod/`.

### Lifecycle: how the Spring context bootstraps

`McAgentFabricMod.onInitializeClient()` only registers tick + chat hooks via `ModEventHooks`. The Spring context is **lazily** created in `initSpringContext()` when the client joins a world (detected by `Minecraft.getInstance().getConnection() != null` going from null → non-null). On disconnect, shutdown is debounced by 60 ticks (~3s) to survive dimension changes / respawns / brief network blips.

`CoreApplication` uses `@Import` (no classpath scanning) — this is intentional, to keep behavior deterministic inside the Fabric classloader. **When adding a new core bean, also register it in `CoreApplication.@Import`.**

### Three-channel event queue (`fabric-mod/queue/`)

`BotEventQueue` is the seam between Minecraft threads, the LLM, and Baritone:

- **Inbound** (`PriorityBlockingQueue<InboundEvent>`) — player commands from `FabricChatHandler`. Dedup window 500ms, capacity 8, cancel/stop/halt → `EventPriority.CANCEL`. A single `mc-agent-inbound-dispatcher` thread drains and calls `LangChain4jService.processInput(...)`.
- **Outbound** (`LinkedBlockingQueue<String>`, cap 4) — chat to send to the player. Drained at 500ms by `mc-agent-outbound-drainer` → `FabricChatSender.send(...)` (uses `sendChat()` — the signed-chat path — **not** `/say`, which gets kicked as broken-chain).
- **Framework buffer** (`FrameworkMessageBuffer`) — Baritone/game-status messages. Throttled (250ms), deduplicated, batched, then injected into LangChain4j memory as `<framework>...</framework>` tagged user messages so the LLM treats them as system events rather than player input.

Additional: `urgentDispatcher` lets `AutonomousObserver` (active mode) call `LangChain4jService.processUrgentObservation(...)` without waiting on the inbound queue.

### Thread safety

Baritone APIs **must** be called on the Minecraft client thread. `ClientThreadExecutor` wraps `Minecraft.getInstance().execute(...)` for that. `FabricBaritoneBridge` uses it whenever it touches Baritone. If you add a new `BotOperations` method that interacts with the game world, route it through `ClientThreadExecutor`.

### LangChain4j wiring

- `Assistant` is a LangChain4j AI-Service interface — the proxy is generated at runtime by `LangChain4jConfig`. The `@SystemMessage` on `Assistant.chat()` is the source of truth for the bot's behavior: framework/player-context tag semantics, pronoun resolution, tool catalogue, safety guidance, observation guidance. **Updating tools or behavior usually means editing this system prompt in lockstep with `MinecraftTools`.**
- `MinecraftTools` exposes `@Tool` methods that LangChain4j auto-discovers. Tools that start a long-running action (navigate, follow, build, mine) send an **immediate** human-readable confirmation via `ChatService` so the player gets feedback without waiting on LLM latency.
- `LangChain4jService` injects two kinds of context into chat memory before calling the model: `<player_context>` (current player + position) on every player message, and `<framework>` (status/observation) on tool/Baritone events.

### Autonomous observation loop

`AutonomousObserver` ticks every `scanIntervalTicks` (default 20 = 1s) and scans for hostile mobs (threats) and configured passive mobs (opportunities). Spatial-bucket + time debounce prevents the same nearby creeper from firing every tick.

- **Passive mode** (default): observations go to `FrameworkMessageBuffer` and sit in LLM memory until the next player turn.
- **Active mode**: observations call `BotEventQueue.triggerUrgentFramework(...)` → urgent dispatcher → LLM, which may react autonomously (e.g. `setSafetyMode(true)`, `fleeFrom(...)`).

Player toggles bypass the LLM entirely — see `FabricChatHandler.handleToggleCommand` (e.g. `agent watch`, `agent active mode`).

### Mixins

Two Mixins (`mc_agent.mixins.json`, compatibility `JAVA_25`):

- `ChatComponentMixin` — `@Inject HEAD` on `ChatComponent#addPlayerMessage` and `addServerSystemMessage` → fires `ModEventHooks.onChatMessage`. `remap = false` because we use Mojmap.
- `MinecraftMixin` — client tick hook → `ModEventHooks.onTick`.

`ModEventHooks` is a tiny static dispatch layer so Mixins don't reach into the mod's stateful objects directly.

### Shadow JAR & classloader

`fabric-mod/build.gradle` relocates **everything** the LLM/Spring stack pulls in (`org.springframework`, `dev.langchain4j`, `com.fasterxml.jackson`, `org.hibernate`, `jakarta.*`, `io.netty`, etc.) under `com.mcagent.shaded.*`. This is required: without relocation, Minecraft's classloader and Fabric mods (especially anything using Netty or Jackson) collide. If you add a new transitive dependency that conflicts at runtime, add a `relocate` line.

`slf4j`, `log4j`, `net.minecraft.*`, and `net.fabricmc.*` are **excluded** from the shadow JAR — the host environment provides these. Logback is excluded from every Spring starter dependency for the same reason.

### Local Baritone dependency

`fabric-mod/build.gradle` resolves `baritone-unoptimized-fabric-1.17.0` from `flatDir '../.local-deps'`. The JAR is checked in (whitelisted in `.gitignore`). At runtime, players install the matching Baritone mod alongside McAgent — we only need API access at compile time, so the dep is `compileOnly`.

## Conventions & gotchas

- **Add a new tool:** implement on `BotOperations` → wire in `FabricBaritoneBridge` (with `ClientThreadExecutor` if it hits Baritone) → expose via `@Tool` on `MinecraftTools` → add a line to the `<available_tools>` section of `Assistant.@SystemMessage`. Missing the system-prompt update is the most common reason a tool "exists but never gets called."
- **Add a new Spring bean in `core/`:** register it in `CoreApplication.@Import`. No component-scan.
- **Touching Baritone:** always via `ClientThreadExecutor`. Spring threads do **not** own the game state.
- **Long Baritone callbacks:** route through `FrameworkMessageBuffer` (not directly into chat memory) so they're throttled/deduped.
- **Sending chat:** use `FabricChatSender.send(...)` (which uses `sendChat()`); never inject `/say` — signed-chain validation will kick the client.
- **Test profile:** `core/` tests run on Java 21. `application-test.yml` / `application-dev.yml` / `application-local.yml` profiles configure H2; production uses Postgres.

## Persistent context

- `llm_memory/` — session continuity notes, design docs per GitHub issue (`issue-N-implementation-brief.md`, `issue-N-validation-guide.md`), and `logbook.md` (dated session summaries). **Read `session-continuity-notes.md` at the start of a new session** to understand what's in flight.
- `reference/` — architecture spec, Baritone API reference, sprint plan. Long-lived, authoritative.

## Git conventions

- Feature branches: `feature/issue-N-short-slug` (the project tracks work via GitHub issues #3–#10+).
- Commit subject format observed in history: `feat:`, `fix:`, `docs:` — short, lowercase, no Claude Code attribution.
- Do not push to `origin` without explicit human approval (per global instructions); John typically tests in-game before pushing.
