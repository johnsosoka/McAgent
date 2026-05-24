# McAgent

LLM-driven autonomous Minecraft agent. Bridges natural-language commands to in-game actions using **LangChain4j** + **Baritone** inside a **Forge 1.20.1** client mod.

## Architecture (Unified)

Single Gradle project with ForgeGradle:

```
src/main/java/com/mcagent/
├── core/          # Pure Java: LLM, memory, tools, config (Spring Boot headless)
│   ├── config/    # Properties, LangChain4j beans, Chroma config
│   ├── memory/    # JPA entities, repos, SQL + vector search
│   ├── tools/     # @Tool definitions for LLM
│   ├── model/     # BotResponse, BotAction, PathResult
│   └── service/   # Assistant, LangChain4jService, SafetyValidator
└── mod/           # Thin Forge wrapper
    ├── MinecraftAgentMod.java    # Forge entry point + Spring bootstrap
    ├── handler/ChatEventHandler.java
    └── service/BaritoneOperationsImpl.java
```

## Quick Start

### Prerequisites
- Java 17
- Gradle 8.x (ForgeGradle requires < 9.0)
- Forge 1.20.1 client with Baritone 1.10.1 installed

### Build
```bash
./gradlew build
# Mod JAR: build/libs/mc-agent-0.1.0-SNAPSHOT.jar
```

### Run Tests
```bash
./gradlew test
```

### Run Client (with mod in dev environment)
```bash
./gradlew runClient
```

### Install
1. Install Forge 1.20.1 client
2. Drop `baritone-standalone-forge-1.10.1.jar` into `.minecraft/mods/`
3. Drop `mc-agent-0.1.0-SNAPSHOT.jar` into `.minecraft/mods/`
4. Set `FIREWORKS_API_KEY` environment variable
5. Launch Forge client and join a vanilla server

### In-Game Usage
Chat commands directed at the bot (containing "bot", "agent", or "mcagent"):
- `agent go to 100 64 200`
- `bot remember this as home base`
- `agent find me some diamonds`
- `bot where is home base?`

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `FIREWORKS_API_KEY` | Yes | Fireworks.ai API key |
| `BOT_NAME` | No | Bot display name |

## Documentation
- [Revised Sprint Plan](llm_memory/revised-plan.md)
- [Merge Plan](llm_memory/merge-plan.md)
- [DevOps Setup Report](llm_memory/devops-setup-report.md)
- [Core Implementation Notes](llm_memory/core-implementation-notes.md)
- [Test Summary](llm_memory/test-summary.md)
- [Architecture Specification](reference/minecraft-bot-architecture-spec.md)
- [Baritone API Reference](reference/baritone-api-reference.md)
