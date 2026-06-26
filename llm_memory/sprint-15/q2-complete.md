# Sprint 15 Q2 — Episodic Memory (Three-Factor Retrieval) — COMPLETE

**Date:** 2026-06-25
**Branch:** `feature/sprint-15-memory`
**Build status:** `:core:test` 386 passed / 0 failed · `:fabric-mod:test` passed · `:fabric-mod:shadowJar` succeeded

## What was built

Generative-Agents-style episodic memory over the existing JPA + embedding stack.

### New package: `core/.../memory/episodic/`

- `AgentEvent` — JPA entity (`agent_event` table) with type, content, importance (1-10), createdAt, sessionId, embeddingJson, relatedTaskId. Indexed on `(sessionId, createdAt DESC)`.
- `AgentEventRepository` — `findBySessionIdOrderByCreatedAtDesc` (pageable), `findByTypeAndSessionId`.
- `EpisodicMemoryService` (interface) — `write(...)` / `retrieve(...)`.
- `DefaultEpisodicMemoryService` — write embeds + LLM-scores importance; retrieve scores all candidate events as `relevance(cosine) + recency(exp-decay) + importance/10.0`, returns top-K.
- `EmbeddingSerializer` — float[] ↔ JSON TEXT serialisation.
- `SessionContext` — Spring bean holding current server address; set by `McAgentFabricMod` at init time.

### Config added (`BotProperties.MemoryProperties`)

`bot.memory.episodic-enabled`, `retrieve-top-k` (3), `recency-decay-hours` (8.0), `max-stored-events-per-session` (5000).

### Write hooks

- `LangChain4jService.processInput` → writes `command` event after each successful LLM call.
- `JobSupervisor.markFailed` → writes `plan_fail` event; plan completion loop → writes `plan_complete` event.

### Read hook

`LangChain4jService.processInput` retrieves top-3 memories before `assistant.chat`, injects as `<memories>…</memories>` framework message.

### Tests added

- `DefaultEpisodicMemoryServiceTest` (24 tests) — ranking math, importance scoring, parse fallback, edge cases.
- `AgentEventRepositoryTest` (6 tests) — `@DataJpaTest` session-scoped queries.
- `LangChain4jServiceTest` — 4 new tests verifying injection and write-after-success behaviour.
- Existing `JobSupervisorTest`, `JobSupervisorConcurrencyTest`, `DefaultTaskExecutorTest`, `LangChain4jServiceRealMemoryTest` updated for new constructor signatures.
