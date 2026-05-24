# McAgent Core — Vector Memory Implementation Notes

## Overview
Added semantic vector search for Minecraft location memory using LangChain4j 1.15.0, with ChromaDB as the primary store and an in-memory fallback for resilience and local development.

## Files Created

### 1. `com.mcagent.core.config.VectorStoreProperties`
- Spring `@ConfigurationProperties` bound to `vector-store.*` in `application.yml`.
- Provides typed access to `vector-store.chroma.*` (host, port, collection, timeout) and `vector-store.in-memory.*` (persist-path).
- Follows the same pattern as existing `LLMProperties` and `BotProperties`.

### 2. `com.mcagent.core.config.ChromaConfiguration`
- Creates a `ChromaEmbeddingStore` bean when the `dev` profile is **not** active (`@Profile("!dev")`).
- Uses the LangChain4j 1.15.0 builder pattern with `ChromaApiVersion.V2` (the stable, non-deprecated API).
- Constructs `baseUrl` from `host` + `port` (`http://localhost:8000`).
- Collection name and timeout are pulled from `VectorStoreProperties`.

### 3. `com.mcagent.core.config.InMemoryVectorStoreConfig`
- Creates an `InMemoryEmbeddingStore<TextSegment>` bean when the `dev` profile **is** active (`@Profile("dev")`).
- Keeps local development lightweight by avoiding the need for a running ChromaDB container.
- Because `ChromaConfiguration` is excluded under `dev`, there is no bean-name collision.

### 4. `com.mcagent.core.memory.VectorMemoryService`
- Core service that indexes and searches `LocationMemoryEntry` objects via embeddings.
- **Embedding text construction**: concatenates name, description, type (humanized), and biome into a rich sentence so the embedding model captures semantic meaning.
- **Metadata**: stores `id`, `name`, `x`, `y`, `z`, `type`, `biome`, `discoveredBy`, and `description` as `TextSegment` metadata so results can be fully reconstructed.
- **Graceful fallback**: if the primary `EmbeddingStore` throws on add or search, a `volatile boolean primaryAvailable` flips to `false` and an internal `InMemoryEmbeddingStore` takes over for the remainder of the process lifetime. This satisfies the "ChromaDB unavailable" resilience requirement.
- `retryPrimaryStore()` is exposed so a health-check or admin call can attempt to reconnect to ChromaDB without restarting.

## Files Modified

### 5. `com.mcagent.core.config.LangChain4jConfig`
- Added an `EmbeddingModel` bean using `OpenAiEmbeddingModel` (Fireworks is OpenAI-compatible).
- Reuses the same `baseUrl`, `apiKey`, and `timeoutSeconds` from `LLMProperties.fireworks`.
- Model name is read from `llm.embedding.model` (`nomic-ai/nomic-embed-text-v1.5` by default).

### 6. `com.mcagent.core.tools.MinecraftTools`
- Wired in `VectorMemoryService` via constructor injection (Lombok `@RequiredArgsConstructor`).
- **`rememberLocation`**: after saving to the SQL-backed `LocationMemoryService`, the saved entity (with its generated UUID) is passed to `vectorMemory.store(...)`. Errors are caught and logged so a vector-index failure does not break the tool response.
- **`findLocation`**: now performs a **two-legged search**:
  1. Semantic vector search via `vectorMemory.searchByDescription(query, 10)`
  2. Exact text search via `locationMemory.search(query)`
  - Results are merged into a `LinkedHashMap<UUID, LocationMemoryEntry>` to deduplicate while preserving vector-match ordering.
  - If both sources are empty, the familiar "No locations found..." message is returned.

### 7. `com.mcagent.core.CoreApplication`
- Added imports for the four new beans (`VectorStoreProperties`, `ChromaConfiguration`, `InMemoryVectorStoreConfig`, `VectorMemoryService`) so they are available in the explicitly configured Spring context used by the mod environment.

### 8. `application.yml`
- Added `vector-store.chroma.timeout-seconds: 5` to align with the new `VectorStoreProperties.ChromaProperties` default.
- Existing `llm.embedding.*` keys were already present and are now consumed by the new `EmbeddingModel` bean.

## Design Decisions

1. **Profile-based store selection** (`dev` → in-memory, everything else → Chroma) keeps local setup friction low while still allowing Chroma testing when desired. Runtime fallback inside `VectorMemoryService` adds a second layer of resilience for production.

2. **Explicit `@Import` in `CoreApplication`** was preserved rather than switching to classpath scanning, because the mod environment (Minecraft Forge) can have classpath oddities; explicit imports are safer.

3. **`OpenAiEmbeddingModel` instead of a Fireworks-specific class**: LangChain4j does not ship a dedicated Fireworks embedding model. Fireworks exposes an OpenAI-compatible `/embeddings` endpoint, so `OpenAiEmbeddingModel` with the Fireworks base URL is the correct, stable approach.

4. **Metadata reconstruction rather than dual persistence**: `VectorMemoryService` stores a lightweight copy of location metadata inside Chroma/in-memory. It does not try to read back from SQL during search. This keeps the vector store self-contained and avoids coupling the two persistence layers at read time.

5. **No `@Primary` annotation needed**: by putting `@Profile("!dev")` on `ChromaConfiguration`, exactly one `EmbeddingStore<TextSegment>` bean exists in any given profile, so injection is unambiguous without extra primary/qualifier annotations.

## Potential Future Improvements
- Persist the in-memory fallback store to disk on JVM shutdown (and reload on startup) using `InMemoryEmbeddingStore.serializeToFile(...)`.
- Add a health-check bean that periodically calls `vectorMemory.retryPrimaryStore()` to recover from transient ChromaDB outages.
- Consider moving `VectorMemoryService` metadata keys (e.g., `"id"`, `"name"`) to named constants to reduce string-typo risk.
