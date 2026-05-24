# Framework Message Tagging

**Date:** 2026-05-23  
**Status:** Implemented and tested

---

## Problem

Baritone progress callbacks ("Arrived at destination", "Mining complete") were being sent to public chat only. The LLM had no awareness of game state changes between player turns, and if the player didn't explicitly ask for status, the bot would act on stale assumptions.

Additionally, if we ever fed chat history back into the LLM, the bot might confuse its own status announcements with player input.

---

## Solution

### `<framework>` XML Tags

All system-generated status messages are wrapped in `<framework>...</framework>` tags before injection into the LLM conversation memory. The `Assistant` system prompt explicitly instructs the model:

```
<context_rules>
- Messages wrapped in <framework>...</framework> are system-generated status reports
  from the game engine (pathfinding progress, mining results, tool execution status, etc.).
  They are NOT player input.
- Messages WITHOUT <framework> tags are from the human player.
- Use framework messages to understand current game state and progress, but do not
  respond to them directly unless the player asks about status.
- When a tool executes, a framework message will confirm its completion. Wait for
  that confirmation before taking further actions based on the result.
</context_rules>
```

### Dual-Channel Delivery

When a Baritone callback fires, two things happen:

| Channel | Content | Recipient |
|---------|---------|-----------|
| **LLM Memory** | `<framework>Arrived at destination.</framework>` | Injected into `ChatMemory` as a `UserMessage` |
| **Public Chat** | `Arrived at destination.` | Visible to the human player |

### Code Path

```
BaritoneOperationsImpl.handlePathEvent(AT_GOAL)
  → BaritoneOperationsImpl.notify("Arrived at destination.")
    → progressCallback.accept("Arrived at destination.")
      → MinecraftAgentMod lambda
        → langChainService.addFrameworkContext("Arrived at destination.")
          → chatMemory.add(UserMessage.from("<framework>Arrived at destination.</framework>"))
        → sendBotChatMessage("Arrived at destination.")
          → mc.player.connection.sendChat(...)
```

---

## Files Changed

| File | Change |
|------|--------|
| `com.mcagent.core.service.Assistant` | System prompt expanded with `<context_rules>` explaining `<framework>` tags |
| `com.mcagent.core.service.LangChain4jService` | Added `addFrameworkContext(String)` method; injects `ChatMemory` bean |
| `com.mcagent.mod.MinecraftAgentMod` | Progress callback lambda now routes to both memory and chat |

---

## Example Conversation Flow

```
Player:  bot navigate to 100 64 200
Bot:     Navigating to (100, 64, 200)

[Baritone walks...]

Framework (injected into LLM memory):
         <framework>Path calculation complete; now executing</framework>

[Baritone arrives...]

Framework (injected into LLM memory):
         <framework>Arrived at destination.</framework>

Chat (player visible):
         Arrived at destination.

Player:  now mine diamonds near here
Bot:     [LLM sees the framework context + player message, calls mine tool]
         Mining DIAMOND_ORE (up to 64 blocks)
```

---

## Future Extensions

- `<framework type="pathing">...</framework>` — typed tags for richer parsing
- `<framework type="inventory">...</framework>` — inventory change reports
- `<framework type="health">...</framework>` — health/hunger alerts
- Filter out redundant framework messages before injection (deduplication)
