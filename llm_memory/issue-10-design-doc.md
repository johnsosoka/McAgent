# Issue #10 — Background Observation Loop: Design Document

**Branch:** `feature/issue-10-background-observation-loop`
**Date:** 2026-05-27
**Status:** In Progress

## Decisions from John

1. **Track both hostile AND passive mobs.** Background observation is an automatic function of the bot — config-based and enabled by default.
2. **Urgent queue is desired.** Active mode observations should trigger immediate LLM calls via a dedicated urgent processing path.
3. **Support both individual and summarized messages, default to individual.**

## Architecture Overview

### New Components

| Component | Package | Responsibility |
|-----------|---------|--------------|
| `AutonomousObserver` | `fabric-mod` | Tick-based world scanning, threat/opportunity detection, debounce, mode switching |
| `ObservationProperties` | `core.config` | Config: enabled, interval, radius, mode, messageMode, debounceSeconds |
| `UrgentQueue` (method in `BotEventQueue`) | `fabric.queue` | Immediate LLM invocation for active mode observations |
| `processUrgentObservation` | `LangChain4jService` | Injects framework context + triggers assistant.chat() synchronously on urgent thread |

### Integration Points

1. **McAgentFabricMod.onClientTick()** — calls `autonomousObserver.onTick()` after connection detection
2. **McAgentFabricMod.initSpringContext()** — instantiates observer, wires `BotEventQueue` + `BotOperations` + config
3. **McAgentFabricMod.shutdownSpringContext()** — shuts down observer to prevent NPEs
4. **FabricChatHandler** — new triggers: `agent watch` / `agent stop watching` to toggle observation at runtime
5. **BotEventQueue** — new `triggerUrgentFramework(String)` method that bypasses normal inbound queue and immediately calls LLM
6. **LangChain4jService** — new `processUrgentObservation(String)` that injects framework context then calls `assistant.chat()`
7. **Assistant system prompt** — new guidance on `<framework>` observations and autonomous behavior

## Reactivity Modes

| Mode | How it works | Implementation |
|------|-------------|----------------|
| **Reactive** (default) | Bot only sees observations when player next speaks | Not changed — this is baseline behavior |
| **Passive** | Observations published as `<framework>` messages to `FrameworkMessageBuffer` | `AutonomousObserver` calls `botEventQueue.publishFramework()` |
| **Active** | Observations trigger immediate LLM call + potential tool invocation | `AutonomousObserver` calls `botEventQueue.triggerUrgentFramework()` |

## Scanning Logic (AutonomousObserver)

```
onTick():
  if disabled → return
  if ++tickCounter < scanIntervalTicks → return
  tickCounter = 0

  // Threat detection (hostile mobs)
  threats = botOps.getNearbyThreats(threatRadius)
  for each threat:
    if not debounced(threat):
      publish("Threat detected: {threat}")
      if activeMode → triggerUrgent()

  // Opportunity detection (passive mobs — configurable)
  if trackPassiveMobs:
    for each passiveType in configured list:
      entities = botOps.getNearbyEntities(passiveType, passiveRadius)
      for each entity:
        if not debounced(entity):
          publish("Opportunity: {entity} nearby")
          if activeMode → triggerUrgent()
```

## Debounce Strategy

- Key: `{type}:{entityId}` or `{type}:{roundedX}:{roundedZ}`
- Window: configurable (default 10 seconds)
- Same threat type within window = suppressed
- When entity leaves radius and returns, it's a "new" observation (clears debounce)

## Configuration (application.yml)

```yaml
bot:
  observation:
    enabled: true
    scanIntervalTicks: 20
    threatRadius: 32
    passiveRadius: 16
    mode: passive
    messageMode: individual
    debounceSeconds: 10
    trackPassiveMobs: true
    passiveMobTypes: Pig, Cow, Chicken, Sheep
```

## Chat Commands

| Command | Action |
|---------|--------|
| `agent watch` | Enable autonomous observation |
| `agent stop watching` | Disable autonomous observation |
| `agent active mode` | Switch to active reactivity |
| `agent passive mode` | Switch to passive reactivity |

## Safety Guardrails

1. No automatic movement without player confirmation (active mode can suggest tools, but player confirmation required by `SafetyValidator` if configured)
2. Max 1 observation per scan interval
3. Debounce prevents duplicate spam
4. Player can disable at any time
5. Observer shuts down cleanly on disconnect

## Testing Strategy

- **Unit tests**: Mock `BotOperations`, `BotEventQueue`, tick through `AutonomousObserver`, verify debounce and mode behavior
- **Integration test**: Deploy JAR, spawn hostile mob, verify bot reacts (future/manual)

## Open Questions Resolved

1. ✅ Both hostile and passive mobs tracked. Configurable.
2. ✅ Urgent queue added as `triggerUrgentFramework()` on `BotEventQueue` + `processUrgentObservation()` on `LangChain4jService`.
3. ✅ Support `individual` (default) and `summary` message modes.

## Files to Modify / Create

### New
- `fabric-mod/src/main/java/com/mcagent/fabric/observer/AutonomousObserver.java`
- `fabric-mod/src/test/java/com/mcagent/fabric/observer/AutonomousObserverTest.java`

### Modified
- `core/src/main/java/com/mcagent/core/config/BotProperties.java` — add `ObservationProperties`
- `core/src/main/java/com/mcagent/core/service/LangChain4jService.java` — add `processUrgentObservation`
- `fabric-mod/src/main/java/com/mcagent/fabric/queue/BotEventQueue.java` — add `triggerUrgentFramework`
- `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java` — watch/stop watching triggers
- `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java` — wire observer lifecycle
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — observation guidance in system prompt

---
*Prepared by AI Lead (OpenCode) for Issue #10 sprint*
