# Revised Sprint Plan: Minecraft AI Agent (Java Native)

**Project:** McAgent — Minecraft LLM-Powered Autonomous Bot  
**Date:** 2026-05-23  
**Status:** Ready for Sprint 1  
**Build Tool:** Maven  
**Mod Loader:** Forge 1.20.1 (primary), architected for future Fabric port  
**LLM Provider:** Fireworks.ai (Kimi k2.6 turbo via OpenAI-compatible API)  
**Database:** H2 (dev) / PostgreSQL (prod) + ChromaDB (vector store)

---

## 1. Architecture Decisions (Post-Clarification)

| Decision | Rationale |
|----------|-----------|
| **Java native, no Node.js** | Durability, type safety, single runtime |
| **Maven (not Gradle)** | User preference; acceptable trade-off vs. ForgeGradle convenience |
| **Forge 1.20.1 client mod** | Baritone requires a real Minecraft client; Forge is most durable/established |
| **Fireworks.ai default LLM** | Kimi k2.6 turbo; OpenAI-compatible endpoint means we use `langchain4j-open-ai` with custom `baseUrl` |
| **ChromaDB from Day 1** | Available for semantic location search even if SQL is primary storage |
| **SQL-first memory** | Player notes and locations stored in relational DB; exposed to LLM as explicit `remember` / `recall` tools |
| **Bundled deployment** | Mod JAR distributed alongside Baritone; bot runs as separate client instance on vanilla servers |
| **Spring Boot in core** | DI, config, and JPA are too valuable to give up; mod entry point bootstraps Spring context carefully |

---

## 2. Module Structure

```
McAgent/
├── pom.xml (parent)
├── mc-agent-core/          # Pure Java: LLM, memory, tools, config
│   ├── src/main/java/com/mcagent/core/
│   │   ├── config/         # Spring Boot properties, beans
│   │   ├── llm/            # LangChain4j service, Fireworks client
│   │   ├── memory/         # JPA repos, Chroma client, location service
│   │   ├── tools/          # @Tool definitions for LLM
│   │   └── service/        # Baritone wrapper, safety validator
│   └── src/main/resources/application.yml
├── mc-agent-mod/           # Thin Forge wrapper
│   └── src/main/java/com/mcagent/mod/
│       ├── MinecraftAgentMod.java
│       └── handler/ChatEventHandler.java
└── llm_memory/             # Team context & planning docs
```

---

## 3. Revised Sprint Breakdown

### Sprint 1: Foundation (Week 1–2)
**Theme:** "The Bot Awakens"

| Task | Deliverable | Owner |
|------|-------------|-------|
| Parent POM + module skeleton | Compiles with `mvn clean compile` | Tech Lead |
| Core module: AppConfig, logging, profiles | `application.yml` with local/prod profiles | Tech Lead |
| Core module: Fireworks.ai LLM client | `LangChain4jService` can chat with Fireworks | Senior Python Engineer* |
| Core module: H2 + JPA setup | `LocationEntity`, `PlayerNoteEntity`, repos | Senior Python Engineer* |
| Core module: ChromaDB client scaffold | `ChromaConfiguration`, `InMemoryVectorStoreConfig` | Senior Python Engineer* |
| Mod module: Forge entry point | `@Mod("mc_agent")` registers on `FMLClientSetupEvent` | Tech Lead |
| Mod module: Chat event handler | `ClientChatReceivedEvent` → parses bot mentions | Tech Lead |
| Mod module: Spring context bootstrap | `MinecraftAgentMod` starts `AnnotationConfigApplicationContext` safely | Tech Lead |
| Integration: Chat → LLM → Chat response | End-to-end: player says "hello" → bot replies via Fireworks | Team |

*Note: Using "senior-python-engineer" agent for Java work is a misnomer; this is a Java project. We will use that agent for code-writing tasks regardless of language — it is capable of writing Java following the spec.*

**Sprint 1 Definition of Done:**
- [ ] `mvn clean install` passes
- [ ] Bot mod loads in Forge client without crashes
- [ ] Player chat message triggers LLM call and bot responds in-game
- [ ] H2 database auto-creates tables on first run
- [ ] Code review complete

---

### Sprint 2: Baritone Bridge + Tools (Week 3–4)
**Theme:** "Giving It Hands"

| Task | Deliverable | Owner |
|------|-------------|-------|
| Baritone API wrapper | `BaritoneService` with pathTo, mine, follow, cancel | Senior Engineer |
| Safety validator | `SafetyValidator` blocks dangerous/deep mining | Senior Engineer |
| Tool registry | `@Tool` methods: navigate, mine, remember, recall | Senior Engineer |
| Intent classifier | `IntentClassifier` uses LLM to classify NAVIGATE / MINE / QUERY | Senior Engineer |
| Action planner | `ActionPlanner` maps intent → Baritone commands | Senior Engineer |
| Chat memory | `MessageWindowChatMemory` (20 msg window) | Senior Engineer |
| Progress: end-to-end NL commands | "go to 100 64 200" → bot walks there | Team |

**Sprint 2 Definition of Done:**
- [ ] 5 natural language commands tested with 80%+ success rate
- [ ] Tool framework allows adding new tool in <30 minutes
- [ ] Safety controls block dangerous actions
- [ ] Chat memory persists across multiple messages

---

### Sprint 3: Memory System (Week 5–6)
**Theme:** "It Remembers"

| Task | Deliverable | Owner |
|------|-------------|-------|
| Location memory service (SQL) | `LocationMemoryService` with CRUD + radius search | Senior Engineer |
| Player notes service (SQL) | `PlayerNoteService` saves/recalls free-text notes | Senior Engineer |
| Manual LLM tools | `rememberLocation(name, desc)`, `findLocation(query)`, `rememberNote(text)` | Senior Engineer |
| ChromaDB semantic search | Locations + notes embedded and searchable by description | Senior Engineer |
| Cross-session persistence | Data survives bot restart (H2 file or PostgreSQL) | Senior Engineer |

**Sprint 3 Definition of Done:**
- [ ] "Remember this as home base" saves to DB + Chroma
- [ ] "Where is home base?" returns coordinates
- [ ] Semantic search: "that cave with diamonds" returns correct location
- [ ] Data persists across 10 restarts

---

### Sprint 4: Polish & Deployment (Week 7–8)
**Theme:** "Production Ready"

| Task | Deliverable | Owner |
|------|-------------|-------|
| Error handling & retry | Exponential backoff for LLM; reconnect for MC | Senior Engineer |
| Unit + integration tests | 80%+ coverage on core module | Senior Engineer |
| Docker Compose stack | Minecraft server + ChromaDB + optional Ollama fallback | DevOps Expert |
| Performance tuning | <2s response for simple commands | Senior Engineer |
| Documentation | README, setup guide, architecture diagram | README Expert |

**Sprint 4 Definition of Done:**
- [ ] 80%+ unit test coverage
- [ ] Docker Compose runs full stack with one command
- [ ] Complete README for new developer onboarding
- [ ] End-to-end demo: new player gives natural commands, bot remembers across restart

---

## 4. Technology Stack Summary

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 17 |
| Build | Maven | 3.9+ |
| Minecraft | Forge | 1.20.1-47.1.0 |
| Pathfinding | Baritone | v1.10.1 (api) |
| LLM Framework | LangChain4j | 0.31.0 |
| LLM Provider | Fireworks.ai | OpenAI-compatible API |
| DI / Config | Spring Boot (starter only, no web) | 3.2.0 |
| Database | H2 (dev) / PostgreSQL (prod) | 2.x / 15 |
| Vector Store | ChromaDB | 0.4.18+ |
| ORM | Spring Data JPA | 3.2.0 |
| HTTP Client | Apache HttpClient 5 | 5.2.1 |
| Utilities | Lombok, Guava | 1.18.30, 32.1.3 |
| Testing | JUnit 5, Mockito, Spring Boot Test | 5.x |

---

## 5. Critical Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Forge + Maven is non-standard | Limit direct Minecraft code; compile against Baritone API + Forge public API only |
| Spring Boot in mod classloader | Use `AnnotationConfigApplicationContext` (not web); no component scanning; explicit `@Bean` config |
| Fireworks.ai model changes | Model name configurable in `application.yml`; fallback to OpenAI or Ollama later |
| Baritone API breaking changes | Abstract behind `BaritoneOperations` interface; pin version in POM |
| Vanilla server anti-cheat | Bot is a real client, not a protocol bot; Baritone moves within vanilla physics |
| Memory leak in long-running bot | Scheduled restarts via Docker; heap profiling in tests |

---

## 6. Immediate Next Steps (Today)

1. **Tech Lead**: Create parent POM and module skeleton
2. **Tech Lead**: Write `MinecraftAgentMod` Forge entry point and `ChatEventHandler`
3. **Senior Engineer**: Implement `LangChain4jService` with Fireworks.ai configuration
4. **Senior Engineer**: Implement JPA entities and repositories for Location + Note
5. **Team**: Verify `mvn clean compile` passes and mod JAR can be dropped into Forge client

---

**Document Status:** Approved for Sprint 1  
**Next Review:** End of Sprint 1
