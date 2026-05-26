# McAgent

LLM-driven autonomous Minecraft agent. Bridges natural-language commands to in-game actions using **LangChain4j** + **Baritone** inside a **Fabric 26.1.2** client mod.

> **Note:** This branch (`feature/fabric-26.1.2-port`) is actively under development. See [llm_memory/session-continuity-notes.md](llm_memory/session-continuity-notes.md) for current status.

## Architecture

Multi-module Gradle project:

```
McAgent/
├── core/              # Pure Java: Spring Boot + LangChain4j (headless)
│   ├── config/        # JPA, LangChain4j, Chroma/in-memory vector store
│   ├── memory/        # JPA entities, repos, SQL + semantic search
│   ├── tools/         # @Tool definitions for LLM (navigate, mine, etc.)
│   └── service/       # Assistant, LangChain4jService
├── fabric-mod/        # Fabric wrapper (Mojang mappings, Mixins)
│   ├── McAgentFabricMod.java      # Entry point + Spring bootstrap
│   ├── FabricChatHandler.java     # Chat interception
│   ├── FabricBaritoneBridge.java  # Baritone API bridge
│   └── mixin/                     # ChatComponentMixin, MinecraftMixin
└── settings.gradle
```

## Prerequisites

- **Java 25** (Zulu JDK recommended for macOS): `brew install --cask zulu-jdk25`
- **Java 21** (for running tests): `brew install openjdk@21`
- **Gradle 8.14.5** (wrapper included)
- **Fabric Loader 0.19.2** for Minecraft 26.1.2
- **Baritone** 1.17.0 (Fabric, unobfuscated — built from `fnltochka/baritone` 26.1 fork)

## Setup

### 1. Configure API Key

Set your Fireworks.ai API key **before** building:

```bash
export FIREWORKS_API_KEY=your_key_here
```

Or edit `core/src/main/resources/application.yml`:
```yaml
llm:
  fireworks:
    api-key: ${FIREWORKS_API_KEY:}
```

### 2. Build

```bash
# Full build + tests (core on Java 21, fabric-mod on Java 25)
./gradlew build

# Just the mod JAR
./gradlew :fabric-mod:shadowJar
```

### 3. Install

```bash
# Install Fabric Loader for 26.1.2
java -jar fabric-installer-1.0.1.jar client -mcversion 26.1.2

# Copy mods to Minecraft
./gradlew :fabric-mod:shadowJar && \
  cp fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar \
  ~/Library/Application\ Support/minecraft/mods/

# Also install Baritone (unobfuscated Fabric build)
cp baritone-unoptimized-fabric-1.17.0.jar \
  ~/Library/Application\ Support/minecraft/mods/
```

### 4. Launch

Select `fabric-loader-0.19.2-26.1.2` in your Minecraft launcher and join a world.

## In-Game Usage

Chat commands directed at the bot (containing "bot", "agent", or "mcagent"):
- `agent what is my location`
- `bot go to 100 64 200`
- `agent come here` (uses followPlayer tool)
- `bot remember this as home base`
- `agent find me some diamonds`
- `agent build a 5x5 oak plank platform at my feet` (uses buildArea tool)
- `agent place a torch here` (uses placeBlockAt tool)
- `agent what do you have in your inventory` (uses getInventorySummary tool)

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `FIREWORKS_API_KEY` | Yes | Fireworks.ai API key |

## Troubleshooting

### "Chat disabled due to broken chain"
- The mod now uses `sendChat()` (signed chat path) instead of `/say`
- Progress messages go to LLM memory only — not public chat
- 3-second rate limiter prevents spam kicks

### "Model not found"
- Verify `llm.fireworks.model` in `application.yml`
- Current default: `accounts/fireworks/models/kimi-k2p5`

### Spring context fails to boot
- Check `~/Library/Application\ Support/minecraft/logs/latest.log`
- Common issues: missing API key, Java version mismatch

## Documentation

- [Session Continuity Notes](llm_memory/session-continuity-notes.md) — Current working session status
- [Research Report](llm_memory/research-report-26.1.2.md) — Forge vs Fabric analysis
- [Porting Plan](llm_memory/fabric-porting-plan.md) — Full execution plan
- [Architecture Spec](reference/minecraft-bot-architecture-spec.md)
- [Baritone API Reference](reference/baritone-api-reference.md)
