# Sprint 11 — Live Status

**Branch:** `feature/sprint-11-foundation` (off `feature/issue-10-background-observation-loop`)

## Bundles dispatched

| Bundle | Items | Owner | Status |
|---|---|---|---|
| A | F1 (memory), Q5 (race fix) | senior-python-engineer | running |
| B | F2 (outbound), F3 (hard-stop) | senior-python-engineer | queued — after A |
| C | F4 (telemetry), F5 (watchdog) | senior-python-engineer | queued — after B |
| D | Q6, P1, P2, P3, P4 | junior-engineer | running |

## File ownership map (avoid concurrent edits)

| File | Bundle A | Bundle B | Bundle C | Bundle D |
|---|:-:|:-:|:-:|:-:|
| `core/config/LangChain4jConfig.java` | ✏️ | | | |
| `core/service/LangChain4jService.java` | ✏️ | | ✏️ | |
| `core/service/Assistant.java` | | | | ✏️ |
| `core/service/SafetyValidator.java` | | | | ✏️ |
| `core/tools/MinecraftTools.java` | | | ✏️ MDC | ✏️ wiring |
| `core/config/BotProperties.java` | ✏️ | ✏️ | | |
| `core/CoreApplication.java` | ✏️ | | ✏️ | |
| `core/memory/chat/*` (new) | ✏️ | | | |
| `core/telemetry/*` (new) | | | ✏️ | |
| `core/resources/application.yml` | ✏️ | ✏️ | | |
| `fabric-mod/queue/BotEventQueue.java` | | ✏️ | ✏️ | |
| `fabric-mod/queue/FrameworkMessageBuffer.java` | | | ✏️ | ✏️ |
| `fabric-mod/FabricChatSender.java` | | ✏️ | | |
| `fabric-mod/FabricChatHandler.java` | | ✏️ | ✏️ | |
| `fabric-mod/FabricBaritoneBridge.java` | | | | ✏️ |

**Conflict resolution:**
- A and D run first (no overlap).
- B runs after A — both touch BotProperties and LangChain4jConfig area; A's changes need to land first so B can extend cleanly.
- C runs after B — both touch BotEventQueue and FabricChatHandler.

## Open questions surfaced

(populated as agents return)

## Next actions

- [ ] Wait for A + D to complete
- [ ] Code-review A + D
- [ ] Dispatch B
- [ ] Code-review B
- [ ] Dispatch C
- [ ] Code-review C
- [ ] Full `./gradlew test`
- [ ] Build shadow JAR
- [ ] Ask John to validate in-game per `e2e-validation/sprint-11-validation.md`
