# McAgent Next Steps

**Date:** 2026-05-23  
**Status:** Core module builds & tests pass (22/22). Mod module is blocked by Forge toolchain.

---

## Immediate Blocker

`mc-agent-mod` cannot compile with plain Maven because Forge modding requires:
- ForgeGradle deobfuscated workspace
- Access mappings (MCP/official) for Minecraft classes
- Runtime patches (Mixins, Access Transformers)

We have `forge-1.20.1-47.1.0-userdev.jar` locally but need a mechanism to use it.

---

## Recommended Next Actions

### 1. Mod Compilation (P0)
**Goal:** Produce a runnable Forge mod JAR.

**Options:**
- **Option A: Minimal Gradle wrapper** — Keep `mc-agent-core` as Maven. Create a small Gradle project (`build.gradle`) for `mc-agent-mod` that uses ForgeGradle plugin. The Gradle project depends on `mc-agent-core` via `mavenLocal()` or `implementation files(...)`. This is the standard Forge mod workflow.
- **Option B: Direct userdev JAR** — Add `forge-1.20.1-47.1.0-userdev.jar` as a system-scoped Maven dependency with the deobfuscated classes. This might work for compile but won't handle runtime obfuscation mapping.
- **Option C: Full migration to Gradle** — Convert entire project to Gradle multi-module. Higher effort, loses Maven preference.

**Recommendation: Option A** — minimal Gradle wrapper for mod, keep Maven for core.

### 2. Standalone LLM Tool Test (P1)
**Goal:** Verify LLM tool-calling works end-to-end without a Minecraft client.

Create a `TestRunner.java` that bootstraps `CoreApplication` Spring context and runs a few test prompts against the real Fireworks.ai API. This proves the tool registry, memory services, and LLM pipeline work.

### 3. Baritone Event Wiring (P1)
**Goal:** Bot reports progress and completion back to chat.

`BaritoneOperationsImpl` currently returns immediately after calling `pathToGoal()`. We need:
- Register `AbstractGameEventListener` for `PathEvent.ARRIVED`, `PathEvent.CALCULATION_FAILED`, etc.
- Track in-progress operations in `MinecraftAgentMod`
- Send chat messages when pathing completes or fails

### 4. Player Name Extraction (P1)
**Goal:** Bot knows who it's talking to.

`ChatEventHandler.extractPlayerName()` uses a fragile heuristic (`<Player>`). Real servers use many formats. Make it configurable or add common patterns.

### 5. Safety Integration (P2)
**Goal:** Actually gate dangerous operations.

`SafetyValidator` exists but is not called in the tool flow. Wire it into `MinecraftTools` or `LangChain4jService` so that before a tool executes, it validates the action. If confirmation is required, ask the player before proceeding.

### 6. Environment Config (P2)
**Goal:** Easy local setup.

- Verify `docker compose up` works for ChromaDB
- Add `start.sh` script that exports `JAVA_HOME` and runs `mvn clean test`

### 7. Prompt Engineering Sprint (P2)
**Goal:** Better LLM responses.

The `Assistant` system message is minimal. Once the tool loop is working, iterate on prompts to get the bot to:
- Report task progress naturally
- Ask clarifying questions for ambiguous commands
- Handle multi-step planning ("build a house" → gather wood → craft planks → build walls)

---

## Decision Log

| Decision | Rationale | Date |
|----------|-----------|------|
| Maven for core, Gradle for mod | Maven preferred for business logic; ForgeGradle is non-negotiable for modding | 2026-05-23 |
| LangChain4j 1.15.0 | Latest stable on Maven Central; supports `ChatModel`/`EmbeddingModel` APIs | 2026-05-23 |
| Fireworks.ai default | User-specified; OpenAI-compatible endpoint means `langchain4j-open-ai` works out of box | 2026-05-23 |
| H2 dev / PostgreSQL prod | Zero-friction local dev with file persistence; PostgreSQL for production durability | 2026-05-23 |
| SQL-first, vector secondary | Player explicitly asked for SQL note/location storage; ChromaDB for semantic search only | 2026-05-23 |

---

## Quick Commands

```bash
# Compile & test core
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
mvn clean test

# Start local infra
docker compose up -d chromadb

# Run standalone LLM test (once TestRunner is created)
mvn -pl mc-agent-core exec:java -Dexec.mainClass=com.mcagent.core.TestRunner
```
