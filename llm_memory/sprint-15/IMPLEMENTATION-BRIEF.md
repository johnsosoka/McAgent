# Sprint 15 — Memory & Polish

**Branch:** `feature/sprint-15-memory` (off Sprint 14)
**Goal:** Cross-session continuity. The bot remembers what worked, what didn't, what you told it.
**Source:** `llm_memory/strategic-review/SYNTHESIS-and-roadmap.md` §4 Sprint 15 + Generative Agents memory architecture (`prior-art-research.md` §11)
**Validation:** `llm_memory/e2e-validation/sprint-15-validation.md`

## Items

### Q2 — Episodic memory (three-factor retrieval)

**Why:** Generative-Agents-style retrieval (relevance + recency + importance) is the reference pattern for long-running agents.

**New package: `core/src/main/java/com/mcagent/core/memory/episodic/`**

```java
@Entity
@Table(name = "agent_event")
public class AgentEvent {
    @Id @GeneratedValue private UUID id;
    private String type;          // "command" | "plan_complete" | "plan_fail" | "observation" | "correction" | "reflection"
    @Column(columnDefinition="TEXT") private String content;
    private int importance;       // 1-10, LLM-scored at write time
    private Instant createdAt;
    private String sessionId;     // server-scoped (per SYNTHESIS Open Q 3)
    @Column(columnDefinition="TEXT") private String embeddingJson;  // float[] serialized
    private String relatedTaskId; // optional UUID string
}

public interface EpisodicMemoryService {
    void write(AgentEvent event);
    List<AgentEvent> retrieve(String queryContext, int topK);
}
```

**Retrieval scoring** (per Generative Agents):
```java
score = relevance * 1.0 + recency * 1.0 + importance / 10.0
```
where:
- `relevance` = cosine similarity of query embedding vs event embedding.
- `recency` = `exp(-ageHours / decayHours)` with `decayHours = 8`.
- `importance` = stored 1-10.

**Importance scoring:** at write time, call a tiny LLM prompt:
```
On a scale of 1-10, how important is the following event for a Minecraft bot's future planning?
1 = trivial routine. 10 = critical correction or major milestone.
Reply ONLY with a number.
Event: {content}
```

**Wiring:**
- `LangChain4jService.processInput` writes a `command` event after each call (after the LLM responds successfully).
- `TaskExecutor` writes `plan_complete` / `plan_fail` events.
- `AutonomousObserver` (sparingly) writes high-importance observations.
- Before each `Assistant.chat`, the service retrieves top-3 events relevant to the player's message and injects them as a `<memories>` framework message.

**Tests:** `EpisodicMemoryServiceTest` with fake embedder; verify ranking math.

### Q4 — Correction memory

**Files:**
- `core/src/main/java/com/mcagent/core/service/Assistant.java` — add `<correction_guidance>`:
  ```
  When the player says something is wrong, incorrect, "that's not what I meant", or expresses dissatisfaction,
  call rememberCorrection(player, context, "what was wrong", "what should happen instead")
  before stopping or revising. Always acknowledge what you noted.
  ```
- New `@Tool rememberCorrection(...)` on `MinecraftTools`. Persists as an `AgentEvent` with `type=correction` and `importance=9` (force-high).
- Retrieval: corrections always retrieved when their relevance > minimum threshold (they should never be missed for relevance-borderline cases).

### P11 — End-of-task reflection

Per Reflexion pattern (`prior-art-research.md` §8).

**File:** new `core/src/main/java/com/mcagent/core/service/ReflectionService.java`.

When a `Plan` completes (success or final failure), `TaskExecutor` calls:
```java
String reflection = reflectionService.reflect(taskSnapshot, framework_messages_during_task);
```

Implementation calls the LLM with a focused prompt:
```
You just completed (or abandoned) a task. Write a 2-3 sentence reflection answering:
- What worked?
- What didn't?
- What would you do differently next similar task?
Be concrete. No filler.
Task: {plan_summary}
Outcome: {state}
Key events: {sampled framework messages}
```

Stored as an `AgentEvent` with `type=reflection`, `importance=6-8` depending on outcome.

### P5 — Shadow JAR validation CI check

**File:** `fabric-mod/build.gradle`. Add a new Gradle task:

```groovy
task validateShadowJar(type: Task) {
    dependsOn shadowJar
    doLast {
        File jar = tasks.shadowJar.archiveFile.get().asFile
        def forbidden = ['com/fasterxml/jackson', 'org/hibernate', 'org/springframework',
                         'dev/langchain4j', 'org/yaml/snakeyaml', 'com/zaxxer']
        def found = []
        new java.util.zip.ZipFile(jar).entries().each { entry ->
            forbidden.each { f ->
                if (entry.name.startsWith(f)) found << entry.name
            }
        }
        if (!found.isEmpty()) {
            throw new GradleException("Shadow JAR contains unshaded packages: " + found.take(10))
        }
    }
}
build.dependsOn validateShadowJar
```

Also remove `com.sun` from the relocation list (it relocates JDK internals — dangerous).

### P7 — Mod menu status panel (GUI) — OPTIONAL / STRETCH

**Files (new):**
- `fabric-mod/src/main/java/com/mcagent/fabric/gui/McAgentScreen.java` — extends `Screen` (vanilla).
- `fabric-mod/src/main/java/com/mcagent/fabric/gui/StatusKeybind.java` — registered keybind, default `M`.
- `fabric-mod/src/main/java/com/mcagent/fabric/gui/StatusModel.java` — reads `JobTracker`, `AutonomousObserver`, `Counters`. No LLM round-trips.

Layout per `ux-design-review.md` R11 ASCII mock.

Only ship if there's appetite — adds GUI surface complexity. Validation S15.8 is marked LOW priority.

## Sequencing

1. Q2 (episodic memory) — foundation; touch lots of services.
2. Q4 (correction memory) — uses Q2.
3. P11 (reflection) — uses Q2.
4. P5 (shadow JAR check) — independent.
5. P7 (GUI) — optional; if doing, last.

## Acceptance

- `sprint-15-validation.md` scenarios pass.
- Week-long usage shows preference learning (S15.10).
- Shadow JAR CI catches a deliberate unshaded dep (S15.9).
