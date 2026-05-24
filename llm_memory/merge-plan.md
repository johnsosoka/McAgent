# McAgent — Merge to Single Gradle Project

**Date:** 2026-05-23  
**Status:** Pre-merge documentation

---

## Why We're Merging

The current split (Maven core + Gradle mod) was a pragmatic choice to satisfy both:
- Your preference for Maven
- ForgeGradle's requirement for Gradle

**But two build systems create friction:**
- `mvn clean install` must succeed before `gradle build` on mod
- Two dependency declarations to maintain
- Two plugin configurations
- Cognitive overhead for every new dependency

A single Gradle project with ForgeGradle is the standard Minecraft mod workflow. The core business logic stays architecturally pure — it just lives in the same source tree.

---

## Pre-Merge Architecture

```
McAgent/
├── pom.xml                          # Parent Maven POM
├── mc-agent-core/                   # Maven module
│   ├── pom.xml
│   └── src/main/java/com/mcagent/core/
│       ├── config/                  # Spring Boot properties
│       ├── llm/                     # LangChain4j service
│       ├── memory/                  # JPA entities, repos, services
│       ├── tools/                   # @Tool definitions
│       └── service/                 # Assistant, BotOperations, SafetyValidator
├── mc-agent-mod/                    # Gradle module
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/com/mcagent/mod/
│       ├── MinecraftAgentMod.java   # Forge entry point
│       ├── handler/ChatEventHandler.java
│       └── service/BaritoneOperationsImpl.java
└── reference/                       # Docs & specs
```

---

## Post-Merge Architecture (Target)

```
McAgent/
├── build.gradle                     # Single Gradle build with ForgeGradle
├── gradle.properties
├── settings.gradle
├── src/main/java/com/mcagent/
│   ├── core/                        # Pure Java (unchanged logic)
│   │   ├── config/
│   │   ├── llm/
│   │   ├── memory/
│   │   ├── tools/
│   │   └── service/
│   └── mod/                         # Forge wrapper
│       ├── MinecraftAgentMod.java
│       ├── handler/
│       └── service/
├── src/test/java/com/mcagent/
│   └── core/                        # All existing tests
├── src/main/resources/
│   ├── application.yml
│   └── META-INF/
│       ├── mods.toml
│       └── accesstransformer.cfg
└── reference/                       # Preserved
```

---

## Migration Steps (Checklist)

1. [ ] **Delete Maven files**
   - `pom.xml` (parent)
   - `mc-agent-core/pom.xml`
   - `mc-agent-mod/pom.xml`
   - Entire `mc-agent-core/` module directory

2. [ ] **Move source files**
   - `mc-agent-core/src/main/java/com/mcagent/core/**` → `src/main/java/com/mcagent/core/`
   - `mc-agent-core/src/test/java/com/mcagent/core/**` → `src/test/java/com/mcagent/core/`
   - `mc-agent-core/src/main/resources/application.yml` → `src/main/resources/application.yml`
   - `mc-agent-mod/src/main/java/com/mcagent/mod/**` → `src/main/java/com/mcagent/mod/`
   - `mc-agent-mod/src/main/resources/META-INF/**` → `src/main/resources/META-INF/`

3. [ ] **Consolidate `build.gradle`**
   - Merge all core Maven dependencies into the single `build.gradle`
   - Add ForgeGradle plugin
   - Configure deobfuscation mappings (official 1.20.1)
   - Add `runs.client` for local testing
   - Configure `reobfJar` for production JAR

4. [ ] **Package name adjustments**
   - `CoreApplication.java` → `McAgentMod.java` (or keep as Spring @Configuration)
   - Ensure `com.mcagent.mod.MinecraftAgentMod` still references `com.mcagent.core.CoreApplication`

5. [ ] **Test verification**
   - `gradle test` should run all 22 existing tests
   - Spring Boot test deps must be on test classpath

6. [ ] **Clean up**
   - Delete `.local-deps/` (ForgeGradle downloads its own)
   - Delete old `mc-agent-mod/` directory

---

## Dependency Mapping (Maven → Gradle)

| Maven Dependency | Gradle Equivalent |
|------------------|-------------------|
| `spring-boot-starter` | `implementation 'org.springframework.boot:spring-boot-starter:3.4.0'` |
| `spring-boot-starter-data-jpa` | `implementation 'org.springframework.boot:spring-boot-starter-data-jpa:3.4.0'` |
| `langchain4j` | `implementation platform('dev.langchain4j:langchain4j-bom:1.15.0')` + `implementation 'dev.langchain4j:langchain4j'` |
| `langchain4j-open-ai` | `implementation 'dev.langchain4j:langchain4j-open-ai'` |
| `langchain4j-chroma` | `implementation 'dev.langchain4j:langchain4j-chroma'` |
| `h2` (runtime) | `runtimeOnly 'com.h2database:h2:2.2.224'` |
| `postgresql` (runtime) | `runtimeOnly 'org.postgresql:postgresql:42.7.3'` |
| `lombok` (provided) | `compileOnly 'org.projectlombok:lombok:1.18.30'` + `annotationProcessor 'org.projectlombok:lombok:1.18.30'` |
| `guava` | `implementation 'com.google.guava:guava:32.1.3-jre'` |
| `spring-boot-starter-test` | `testImplementation 'org.springframework.boot:spring-boot-starter-test:3.4.0'` |
| `httpclient5` | `implementation 'org.apache.httpcomponents.client5:httpclient5:5.2.1'` |

---

## Build Commands (Post-Merge)

```bash
# Run all tests
./gradlew test

# Build mod JAR (reobfuscated for production)
./gradlew build

# Run client with mod (ForgeGradle handles the dev environment)
./gradlew runClient

# Install core JAR to local Maven (if other projects need it)
./gradlew publishToMavenLocal
```

---

## Decision Rationale

| Factor | Pre-Merge | Post-Merge |
|--------|-----------|------------|
| Build tools | 2 (Maven + Gradle) | 1 (Gradle) |
| Artifacts | 2 JARs (core + mod) | 1 JAR (mod with core shaded/bundled) |
| Forge support | Excellent | Excellent |
| Test runner | Maven Surefire | Gradle Test |
| CI complexity | Two steps | One step |
| Dev friction | Must `mvn install` before `gradle build` | Single `./gradlew build` |

---

## Files to Preserve

All `llm_memory/` docs and `reference/` docs are preserved unchanged.

## Files to Delete

- `pom.xml`
- `mc-agent-core/pom.xml`
- `mc-agent-mod/pom.xml`
- `mc-agent-mod/build.gradle` (replaced by root `build.gradle`)
- `mc-agent-mod/settings.gradle`
- `mc-agent-mod/gradle.properties`
- `.local-deps/` directory

---

**Action:** Proceed with migration per checklist above.
