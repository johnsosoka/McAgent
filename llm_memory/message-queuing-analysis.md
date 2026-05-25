# Message Queuing & Harness Architecture Analysis

**Date:** 2026-05-24
**Project:** McAgent (Fabric 26.1.2 + Spring Boot / LangChain4j)
**Status:** Gap analysis — no formal message queue exists today
**Related:** [Baritone Integration Research](baritone-integration-research.md)

---

## What Exists Today

### 1. Chat Input Threading (`FabricChatHandler.java`)

- A `newSingleThreadExecutor()` named `mc-agent-chat` serializes player chat commands.
- Each command is submitted as a `Runnable` that calls `langChainService.processInput()`.
- **This is a thread pool, not a queue with semantics.** There is no priority, deduplication, or backpressure.

### 2. Baritone Event Callbacks (`FabricBaritoneBridge.java`)

- `AbstractGameEventListener` registered on Baritone's event bus.
- `onPathEvent(PathEvent)` and `onTick(TickEvent)` fire directly on the main client thread.
- These immediately call `progressCallback.accept(msg)`, which routes to:
  - `langChainService.addFrameworkContext(msg)` → injects into `ChatMemory`
  - `FabricChatSender.send(msg)` → posts to Minecraft chat
- **No buffering, throttling, or deduplication.** A stuck path can flood `NEXT_CALC_FAILED` every tick.

### 3. Mixin Hooks (`ChatComponentMixin`, `MinecraftMixin`)

- `ChatComponentMixin` intercepts `addPlayerMessage` and `addServerSystemMessage` at `HEAD`.
- `MinecraftMixin` intercepts `tick()` at `HEAD`.
- Both delegate through `ModEventHooks` static callbacks into `McAgentFabricMod`.
- **This is synchronous event wiring, not an async queue.** Chat and tick events are processed inline with the client loop.

### 4. LLM Memory Injection (`LangChain4jService.java`)

- `addFrameworkContext(String)` wraps the message in `<framework>` tags and appends it to `ChatMemory` as a `UserMessage`.
- `ChatMemory` is a LangChain4j interface (default implementation is unbounded or windowed).
- **Current configuration:** In-memory chat memory. No explicit max-token or max-message limit is enforced.

---

## Identified Gaps

| Gap | Current Behavior | Risk |
|-----|-----------------|------|
| **No buffered event queue** | Chat commands and framework events compete for the same `ChatMemory` via direct, unbuffered calls. | LLM sees an interleaved, unordered, potentially overwhelming stream of inputs. |
| **No deduplication** | `NEXT_CALC_FAILED` increments a 100-tick counter but still emits every tick. Other framework messages (e.g., repeated "Pathing stopped before reaching the goal") can spam memory. | Wastes LLM context window, slows response time, increases token cost. |
| **No priority system** | Single-threaded FIFO executor. A "cancel" command from the player cannot preempt a pending long-running LLM call. | Poor UX — player says "stop" but the bot is still processing an old navigate command. |
| **No backpressure** | `ChatMemory` grows unbounded during active sessions. Every tick event, path event, and player message appends. | Exceeds context window, degrades LLM performance, possible OOM if memory is persisted. |
| **Thread safety gap** | Spring Boot core calls `processInput()` on the chat executor thread. If the LLM invokes a tool that touches Baritone or Minecraft client state, it happens off the main client thread. | Baritone and Minecraft client are single-threaded. Off-thread access can cause `ConcurrentModificationException`, race conditions, or silent corruption. |
| **No batching** | Each framework message is injected individually. A pathing operation may generate 5+ status messages in rapid succession. | LLM processes each as a separate memory entry. Batching into a single status digest would be more efficient. |

---

## Recommended Architecture

Introduce a lightweight **event bus / ring buffer** in the `fabric-mod` layer with three distinct queues:

```
┌─────────────────────────────────────────────────────────────┐
│                     Fabric Client Thread                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │ Inbound Queue│  │ Framework Q │  │ Outbound Q  │        │
│  │ (Player Chat)│  │(Baritone/game│  │ (LLM→Chat)  │        │
│  │              │  │   status)   │  │             │        │
│  │ - Priority   │  │ - Throttle  │  │ - Rate limit│        │
│  │ - Deduplicate│  │ - Deduplicate│ │ - Batching  │        │
│  │ - Backpressure│ │ - Batch     │  │             │        │
│  └──────┬───────┘  └──────┬──────┘  └──────┬──────┘        │
│         │                 │                 │               │
│         ▼                 ▼                 ▼               │
│  ┌─────────────────────────────────────────────────┐      │
│  │        Event Dispatcher (main thread)           │      │
│  │  - Ensures all Minecraft/Baritone calls run    │      │
│  │    on the client thread via execute(...)       │      │
│  │  - Routes to Spring core for LLM processing    │      │
│  └─────────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### Queue Specifications

#### 1. Inbound Queue (Player Commands)

| Property | Value |
|----------|-------|
| Type | Priority blocking queue |
| Capacity | 8 (small — we don't want command backlog) |
| Priority | `CANCEL` > `EMERGENCY` > `NORMAL` |
| Deduplication | Drop duplicate text within 500ms |
| Overflow | Drop oldest non-priority item; notify player with "I'm busy, one moment" |

#### 2. Framework Queue (Game Status)

| Property | Value |
|----------|-------|
| Type | Ring buffer / circular buffer |
| Capacity | 32 entries |
| Throttle | Max 1 message per 250ms into LLM memory |
| Deduplication | Collapse identical messages within throttle window |
| Batching | If 3+ messages queued, send a single batched digest |
| Overflow | Oldest entry overwritten (status is ephemeral) |

#### 3. Outbound Queue (LLM Chat Output)

| Property | Value |
|----------|-------|
| Type | Linked blocking queue |
| Capacity | 4 |
| Rate limit | 1 message per 3 seconds (already implemented) |
| Overflow | Drop oldest; log warning |

### Thread Safety Rule

**All Baritone and Minecraft client calls must execute on the main client thread.**

If the Spring Boot core (running on the chat executor thread) needs to invoke a Baritone tool, dispatch it:

```java
Minecraft.getInstance().execute(() -> {
    // Safe: now on main client thread
    baritone.getCustomGoalProcess().setGoalAndPath(goal);
});
```

Similarly, if Baritone events on the main thread need to send data to the Spring core, use the framework queue to cross the thread boundary safely.

---

## Implementation Plan

| Step | Task | Files to Change | Effort |
|------|------|-----------------|--------|
| 1 | Add `BotEventQueue` class in `fabric-mod` | New: `BotEventQueue.java` | Low |
| 2 | Add `FrameworkMessageBuffer` with dedup + throttle | New: `FrameworkMessageBuffer.java` | Low |
| 3 | Wire `FabricChatHandler` to use inbound queue | `FabricChatHandler.java` | Low |
| 4 | Wire `FabricBaritoneBridge` to use framework queue | `FabricBaritoneBridge.java` | Low |
| 5 | Add thread-dispatch utility for tool execution | New: `ClientThreadExecutor.java` | Low |
| 6 | Wire `FabricChatSender` to use outbound queue | `FabricChatSender.java` | Low |
| 7 | Add memory backpressure / windowing config | `LangChain4jConfig.java`, `application.yml` | Low |
| 8 | Integration testing — rapid chat + pathing events | New tests / manual test plan | Medium |

---

## Relationship to Baritone Integrations

As we add more Baritone capabilities (player scanning, building, inventory checks), the volume of framework messages and tool invocations will increase. A robust queuing layer is the **foundation** that makes the expanded integrations reliable. Without it:

- `scanForBlocks` running every tick could spam the LLM.
- Building progress callbacks would interleave with player chat.
- Inventory queries triggered by the LLM would compete with pathing callbacks.

**Recommendation:** Implement the queuing layer **before or alongside** the first Baritone expansion. It is the infrastructure that the new capabilities depend on.

---

*Analysis generated for McAgent technical team. Recommend pairing this with the Baritone integration issues and scheduling the queue work as Issue #1 or parallel Issue #0.*
