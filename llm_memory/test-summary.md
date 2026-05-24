# McAgent Core — Unit Test Summary

## Coverage

### `LocationMemoryServiceTest` (`@DataJpaTest`)
- **save** — verifies entity persistence and ID generation
- **findByName** — case-insensitive lookup by name
- **search** — text search across `name` and `description`
- **findNearby** — Euclidean radius query from a `BotOperations.Location`
- **listAll** — returns every stored location
- **delete** — removes a location by UUID and confirms absence

### `PlayerNoteServiceTest` (`@DataJpaTest`)
- **save** — verifies note persistence with player, content, and tags
- **findByPlayer** — case-insensitive lookup ordered by `createdAt` descending
- **search (content)** — full-text match inside `content`
- **search (tags)** — full-text match inside `tags`
- **delete** — removes a note by UUID and confirms absence

### `LangChain4jServiceTest` (`@ExtendWith(MockitoExtension.class)`)
- **success path** — mocked `Assistant.chat(...)` returns a `BotResponse`; result is forwarded unchanged
- **failure path** — mocked `Assistant.chat(...)` throws `RuntimeException`; service returns a LOW-confidence fallback message containing the error text

### `SafetyValidatorTest` (plain unit test)
- **safe mining** — `MINE` with a non-dangerous target is approved without confirmation
- **dangerous blocks** — `MINE` with targets containing `TNT` or `LAVA` triggers `requiresConfirmation`
- **safe actions** — `NAVIGATE`, `FOLLOW`, `REMEMBER_LOCATION`, `QUERY`, `CANCEL` are auto-approved
- **default** — unlisted action types (`BUILD`) fall through to `approved()`

## Assumptions
1. H2 is available on the test classpath (declared in `pom.xml` with `runtime` scope; Spring Boot Test auto-configures it for `@DataJpaTest`).
2. The repositories (`LocationRepository`, `PlayerNoteRepository`) are picked up automatically by `@DataJpaTest` — no explicit `@Import` is required for them.
3. `BotProperties.SafetyProperties.getDangerousBlocks()` returns `List<String>`; the test constructs a real `BotProperties` instance rather than mocking it because the object is a simple POJO.
4. `Assistant` is a plain interface in these tests; the real LangChain4j proxy generation is not exercised.
5. `LocationMemoryServiceTest` uses `@Import(LocationMemoryService.class)` because `@DataJpaTest` disables full component scanning by default.

## What Needs Integration Tests Later
- **ChromaDB vector search** — The repositories currently use SQL text search; if vector-backed semantic search is wired in later, it will need an integration test against an embedded Chroma or Testcontainers instance.
- **LangChain4j `Assistant` proxy** — Real LLM calls (or a local model) should be exercised in an integration suite to verify prompt formatting, system-message injection, and JSON deserialization into `BotResponse`.
- **End-to-end bot action pipeline** — `SafetyValidator` → `LangChain4jService` → actual `BotOperations` execution in a running Minecraft/Baritone environment.
- **Radius search accuracy** — While the unit test proves the SQL query runs, a larger dataset integration test would verify spatial correctness with floating-point edge cases.
