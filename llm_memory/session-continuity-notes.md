# McAgent Fabric 26.1.2 Port — Working Session Notes

**Date:** 2026-05-24
**Branch:** `feature/fabric-26.1.2-port`
**Status:** ALMOST WORKING — Spring boots, LLM responds, tools may or may not execute

---

## Architecture Decisions

### Tool-Based Architecture (Current)
- `Assistant.chat()` returns `String` (NOT `BotResponse`)
- LangChain4j automatically discovers and invokes `@Tool` methods on `MinecraftTools`
- Tools execute synchronously; return values fed back to LLM
- Final conversational text returned as `String`

### What Works
- ✅ Multi-module Gradle: `core/` (Java 21) + `fabric-mod/` (Java 25)
- ✅ Spring context boots: 45 beans initialized
- ✅ H2 database + JPA (`JpaConfig.java` with explicit EMF/TxManager/DataSource)
- ✅ Dev profile active → in-memory vector store (no ChromaDB needed)
- ✅ Baritone unobfuscated JAR loads at runtime
- ✅ Baritone API `compileOnly` — mod provides implementation
- ✅ Mojang mappings (mojmap) for 26.1.2
- ✅ Mixins replace Fabric API events (`ChatComponentMixin`, `MinecraftMixin`)
- ✅ Thread-safe `FabricChatSender` with rate limiting (3s cooldown)
- ✅ Chat sanitization (strips backticks, control chars, non-ASCII)
- ✅ Spring tests pass (22/22 on Java 21)
- ✅ Shadow JAR bundles all dependencies with relocation
- ✅ API key hardcoded fallback in `LangChain4jConfig`
- ✅ `followPlayer()` tool added to `MinecraftTools`

### What MAY Still Be Broken
- ⚠️ **Tool execution unclear** — logs show LLM responding conversationally but no evidence tools are being called
- ⚠️ **Chat sending** — just switched from `/say` back to `sendChat()`; may re-trigger "broken chain" spam kicks if rate limiting fails
- ⚠️ **Progress callback** — only goes to LLM memory, not chat (good, but verify no double-sending)

### Installed Mods
```
~/Library/Application Support/minecraft/mods/
├── baritone-unoptimized-fabric-1.17.0.jar  (patched: minecraft >=26.1)
├── mc-agent-fabric-0.2.0-SNAPSHOT-all.jar  (57M → 55M after compileOnly Baritone)
```

### Key Files
| File | Purpose |
|------|---------|
| `core/src/main/java/com/mcagent/core/config/JpaConfig.java` | Explicit JPA infrastructure |
| `core/src/main/java/com/mcagent/core/config/LangChain4jConfig.java` | ChatModel, EmbeddingModel, Assistant builder with `.tools(tools)` |
| `core/src/main/java/com/mcagent/core/service/Assistant.java` | `String chat(String)` — tool-based |
| `core/src/main/java/com/mcagent/core/tools/MinecraftTools.java` | `@Tool` methods for LLM |
| `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` | Entry point, dev profile, progress callback |
| `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` | Chat interception, LLM delegation |
| `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatSender.java` | Rate-limited, sanitized chat send |
| `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java` | Baritone API bridge |
| `fabric-mod/src/main/java/com/mcagent/fabric/mixin/ChatComponentMixin.java` | Intercepts chat messages |
| `fabric-mod/src/main/java/com/mcagent/fabric/mixin/MinecraftMixin.java` | Intercepts tick loop |

---

## Remaining Tasks for Next Session

### P0: Verify Tool Execution
**Question:** When player types "agent come here", does the LLM actually call `followPlayer("lexicon_social")` or just reply conversationally?

**How to verify:**
```bash
cat ~/Library/Application\ Support/minecraft/logs/latest.log | grep -i 'Tool:\|followPlayer\|navigateTo\|sendMessage\|getCurrentPosition'
```
If no tool logs appear, the LLM isn't invoking tools — likely because:
- Model doesn't support function calling (check Fireworks kimi-k2p5 capabilities)
- LangChain4j tool discovery isn't working in the shaded classpath
- The system prompt doesn't explicitly tell the model to use tools

**Potential fix:** Add `@UserMessage` or `@AiService` configuration to force tool usage. Or switch to a model that explicitly supports OpenAI function calling.

### P0: Verify Chat Output
**Question:** Does `sendChat()` actually show messages in-game?

**How to verify:**
- Have player type `agent what is my location`
- Check if bot responds in chat with `<BotName> ...`
- If "broken chain" appears, the rate limiter or progress callback is leaking

### P1: Remove Unused Files
- `BotResponse.java` — no longer used (String return type)
- `BotAction.java` — no longer used (tools execute directly)
- `SafetyValidator.java` — orphaned (was for BotResponse validation)
- Clean up dead code after confirming tools work

### P1: Update Tests
- `LangChain4jServiceTest.java` — updated to String return
- Add integration test that mocks tool execution

### P2: Model Switch
- `kimi-k2p5` may not support function calling properly
- Consider: `accounts/fireworks/models/llama-v3p1-8b-instruct` or `accounts/fireworks/models/qwen2p5-72b-instruct`
- Must verify model supports OpenAI-compatible function calling

### P2: Bot Name Detection
- Current trigger words: `bot`, `agent`, `hey bot`, `mcagent`
- Consider adding bot's actual Minecraft username to triggers

---

## Build Commands
```bash
# Full build
./gradlew build

# Just fabric mod
./gradlew :fabric-mod:shadowJar

# Install to Minecraft
./gradlew :fabric-mod:shadowJar && \
  cp fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar \
  ~/Library/Application\ Support/minecraft/mods/

# Check logs
cat ~/Library/Application\ Support/minecraft/logs/latest.log | grep -i 'mcagent\|Tool:\|Error'
```

## Git Status
Branch: `feature/fabric-26.1.2-port`
Unpushed commits: ~15 commits with porting work
DO NOT push without human review.

## Key Dependencies
- Minecraft: 26.1.2 (mojmap)
- Fabric Loader: 0.19.2
- Baritone: 1.17.0 (unoptimized fabric fork, built from fnltochka/baritone 26.1)
- Spring Boot: 3.4.0
- LangChain4j: 1.15.0
- Java: 25 (fabric-mod), 21 (core tests)
- Gradle: 8.14.5
- Unimined: 1.4.2-SNAPSHOT

## Next Session Should Start With
1. Read this file
2. Check latest Minecraft logs for tool execution evidence
3. If no tools executing → investigate model function-calling support or LangChain4j configuration
4. If tools executing but chat not showing → investigate `sendChat()` vs server chat signing
5. If both working → celebrate, clean up dead code, prepare for merge

---
**Prepared by:** AI Lead (OpenCode)
**For:** John Sosoka — Next session continuation
