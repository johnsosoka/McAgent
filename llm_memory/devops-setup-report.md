# McAgent DevOps Setup Report

**Date:** 2026-05-23
**Project:** /Users/john/code/projects/McAgent
**Agent:** OpenCode (technical lead)

---

## 1. Environment Setup

### Initial State
- **Java:** Not installed (`Unable to locate a Java Runtime`)
- **Maven:** Not installed (`command not found: mvn`)
- **Homebrew:** 5.1.12 (available)

### Actions Taken
1. Installed OpenJDK 17 and Maven via Homebrew:
   ```bash
   brew install openjdk@17 maven
   ```
2. Configured `JAVA_HOME` for the session to point to the Homebrew keg (system symlink requires sudo password, which was unavailable):
   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
   export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
   ```

### Verification
```
openjdk version "17.0.19" 2026-04-21
Apache Maven 3.9.16
Java version: 17.0.19, vendor: Homebrew
OS: macOS aarch64
```

---

## 2. Compilation Status

### First Attempt: `mvn clean compile`
**Result:** BUILD FAILURE in `mc-agent-core`

#### Error 1: LangChain4j API Mismatch
```
[ERROR] LangChain4jConfig.java:[6,34] cannot find symbol
  symbol:   class ChatLanguageModel
  location: package dev.langchain4j.model.chat
```

**Root Cause:** The POM specifies LangChain4j `1.15.0`. In this version, `ChatLanguageModel` was renamed to `ChatModel`, and `AiServices.builder(...).chatLanguageModel(...)` was renamed to `.chatModel(...)`.

**Fix Applied:** Updated `LangChain4jConfig.java` (compilation blocker):
- `import dev.langchain4j.model.chat.ChatLanguageModel;` -> `ChatModel`
- Method name `chatLanguageModel(...)` -> `chatModel(...)`
- `AiServices` builder call `.chatLanguageModel(...)` -> `.chatModel(...)`

#### Error 2: Java Record Accessor Collision
```
[ERROR] SafetyValidator.java:[42,40] invalid accessor method in record
  (return type of accessor method approved() must match the type of record component approved)
```

**Root Cause:** The `ValidationResult` record had components named `approved` and `requiresConfirmation`, but also defined static factory methods with the exact same names (`approved()`, `requiresConfirmation()`). Java records auto-generate accessor methods with the same names, causing a signature collision.

**Fix Applied:** Renamed record components and updated all test references:
- `approved` -> `isApproved`
- `requiresConfirmation` -> `needsConfirmation`
- Updated `SafetyValidatorTest.java` to use `.isApproved()` and `.needsConfirmation()`

### Second Attempt: `mvn clean compile`
**Result:** `mc-agent-core` SUCCESS; `mc-agent-mod` FAILURE

#### Error 3: Forge & Baritone Dependency Resolution
```
[ERROR] Could not find artifact net.minecraftforge:forge:jar:1.20.1-47.1.0
[ERROR] Could not find artifact com.github.cabaletta:baritone:jar:api:v1.10.1
```

**Root Cause:**
- Forge does not publish a plain `jar` artifact for `net.minecraftforge:forge`. The only usable JAR is the `universal` classifier, but even that is obfuscated and lacks compile-time source-level classes (e.g., `net.minecraftforge.eventbus.api.SubscribeEvent`).
- Baritone's `api` classifier is not published on JitPack. The GitHub release provides `baritone-api-1.10.1.jar`, but it is obfuscated (class names like `baritone/a.class`).

**Fixes Applied:**
1. Downloaded `forge-1.20.1-47.1.0-universal.jar` from Forge Maven and installed it locally via `mvn install:install-file`.
2. Downloaded `baritone-api-1.10.1.jar` from GitHub releases and installed it locally via `mvn install:install-file`.
3. Updated `pom.xml` (parent) to add `<classifier>universal</classifier>` to the Forge dependency in `dependencyManagement`.
4. Updated `mc-agent-mod/pom.xml` to add `<classifier>universal</classifier>` to the Forge dependency declaration.

### Third Attempt: `mvn clean compile` (with local deps)
**Result:** `mc-agent-core` SUCCESS; `mc-agent-mod` FAILURE

#### Error 4: Missing Forge/Minecraft Source-Level Classes
```
[ERROR] cannot find symbol: class SubscribeEvent
[ERROR] cannot find symbol: class Mod
[ERROR] package net.minecraft.world.level.block does not exist
[ERROR] package lombok.extern.slf4j does not exist
[ERROR] cannot find symbol: class IMineBehavior
```

**Root Cause:** The Forge `universal` JAR is obfuscated/reobfuscated and does **not** contain the deobfuscated `net.minecraftforge.eventbus.api`, `net.minecraftforge.fml.common.Mod`, or `net.minecraft.*` classes that the mod source imports. Forge mod development requires **ForgeGradle**, which patches and deobfuscates a dedicated workspace. Plain Maven cannot replicate this toolchain.

The Baritone API JAR from releases is also obfuscated, so `IMineBehavior` does not exist under that readable name.

Additionally, the mod module uses Lombok (`@Slf4j`, `@RequiredArgsConstructor`) but never declared `lombok` as a dependency.

**Fix Applied:**
- Moved `mc-agent-mod` behind a Maven profile (`-Pmod`) in the parent `pom.xml`. The default `mvn clean compile` now builds `mc-agent-core` only.
- Added the missing `lombok` dependency to `mc-agent-mod/pom.xml` for when the profile is active.

### Final Compilation Result
```
[INFO] McAgent Parent ..................................... SUCCESS
[INFO] McAgent Core ....................................... SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

**Summary:** `mc-agent-core` (the LangChain4j/Spring Boot business-logic module) compiles and tests cleanly. `mc-agent-mod` requires a ForgeGradle workspace and cannot be built with plain Maven.

---

## 3. Test Status

### Initial Test Run (`mvn clean test`)
**Result:** BUILD FAILURE

#### Error: Missing `@SpringBootConfiguration`
```
[ERROR] LocationMemoryServiceTest » IllegalState Unable to find a @SpringBootConfiguration
[ERROR] PlayerNoteServiceTest » IllegalState Unable to find a @SpringBootConfiguration
```

**Root Cause:** `mc-agent-core` is a library module with no `@SpringBootApplication`. The `@DataJpaTest` classes could not locate a configuration root.

**Fix Applied:** Created `mc-agent-core/src/test/java/com/mcagent/core/TestApplication.java` with `@SpringBootApplication`. This is a test-only bootstrap class; no production source logic was modified.

### Final Test Result
```
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Test breakdown:
- `LocationMemoryServiceTest`: 6 passed
- `PlayerNoteServiceTest`: 5 passed
- `LangChain4jServiceTest`: 2 passed
- `SafetyValidatorTest`: 9 passed

---

## 4. Infrastructure Files Created

### `docker-compose.yml`
- **ChromaDB** service on port `8000` with persistent volume `chroma_data`
- **Ollama** service on port `11434` with persistent volume `ollama_data`
- Includes healthcheck for ChromaDB

### `.github/workflows/ci.yml`
- Triggers on `push` and `pull_request` to `main`
- Sets up JDK 17 (Temurin) with Maven caching
- Runs `mvn clean test`
- Includes a commented-out step for building the mod profile with a note that it requires ForgeGradle

---

## 5. Files Modified

| File | Change |
|------|--------|
| `pom.xml` | Added `<classifier>universal</classifier>` to Forge dependency; moved `mc-agent-mod` to `-Pmod` profile |
| `mc-agent-core/pom.xml` | (no changes) |
| `mc-agent-mod/pom.xml` | Added Forge `universal` classifier; added missing `lombok` dependency |
| `mc-agent-core/src/main/java/.../LangChain4jConfig.java` | `ChatLanguageModel` -> `ChatModel`; `chatLanguageModel()` -> `chatModel()` |
| `mc-agent-core/src/main/java/.../SafetyValidator.java` | Record components renamed: `approved` -> `isApproved`, `requiresConfirmation` -> `needsConfirmation` |
| `mc-agent-core/src/test/java/.../SafetyValidatorTest.java` | Updated accessor method calls to match renamed record components |
| `mc-agent-core/src/test/java/com/mcagent/core/TestApplication.java` | **Created** — test-only `@SpringBootApplication` bootstrap |

---

## 6. Outstanding Issues / Notes

1. **Forge Mod Compilation:** `mc-agent-mod` cannot be compiled with plain Maven. It requires the ForgeGradle ecosystem (deobfuscated workspace, ATs, Mixins). To build the mod, use `mvn clean compile -Pmod` inside a ForgeGradle setup, or migrate the mod module to a Gradle subproject.
2. **Baritone API:** The JitPack artifact for Baritone (`com.github.cabaletta:baritone:v1.10.1:api`) does not exist. We installed the GitHub release JAR locally, but it is obfuscated and missing readable class names like `IMineBehavior`.
3. **Lombok in mod:** Was missing from `mc-agent-mod/pom.xml`; now added.
4. **CI Caching:** The GitHub Actions workflow uses `actions/setup-java` with `cache: maven`, which will speed up subsequent builds.

---

## 7. Quick Reference

```bash
# Compile core (default)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
mvn clean compile

# Run tests
mvn clean test

# Start local infra
docker compose up -d

# Build mod (requires ForgeGradle workspace — will still fail in plain Maven)
mvn clean compile -Pmod
```
