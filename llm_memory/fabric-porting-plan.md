# McAgent Fabric 26.1.2 Porting Plan

**Target:** Port McAgent from Forge 1.20.1-47.1.0 to Fabric 26.1.2-64.0.8
**Date:** 2026-05-24
**Status:** Ready for execution
**Dependencies:** Java 25, Fabric API 26.1.2, Baritone 26.1 Fabric fork

---

## Executive Summary

This plan details the complete port of McAgent from Forge 1.20.1 (Java 17) to Fabric 26.1.2 (Java 25). The core AI logic (Spring Boot + LangChain4j + JPA + H2) is loader-agnostic and will be preserved. Only the thin Minecraft mod wrapper needs rewriting from Forge API to Fabric API.

**Estimated Effort:** 2-3 weeks
**Critical Path:** Java 25 setup → Fabric toolchain → Baritone fork integration → Chat event rewrite → End-to-end test

---

## Phase 0: Environment & Prerequisites (Day 1-2)

### 0.1 Install Java 25
```bash
# Option A: Azul Zulu 25 (recommended - Baritone PR uses this)
brew install --cask zulu-jdk25
# Verify: java -version should show 25.x

# Option B: OpenJDK 25
brew install openjdk@25
```

**Why Zulu:** The Baritone 26.1 PR uses Zulu JDK 25 because Temurin 24+ drops jmods, which breaks ProGuard during Baritone's build process.

### 0.2 Install Forge 26.1.2 Client (for vanilla testing)
```bash
# Strip macOS quarantine
xattr -d com.apple.quarantine ~/Downloads/forge-26.1.2-64.0.8-installer.jar

# Install client
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
$JAVA_HOME/bin/java -jar ~/Downloads/forge-26.1.2-64.0.8-installer.jar \
  --installClient ~/Library/Application\ Support/minecraft
```

### 0.3 Verify Vanilla 26.1.2 Launch
- Open Minecraft launcher
- Select version `26.1.2-forge-64.0.8`
- Launch and confirm game loads to main menu
- This proves Java 25 and Forge 26.1.2 work on the machine

### 0.4 Project Structure Decision

**Single Gradle project with two subprojects:**
```
McAgent/
├── core/          # Pure Java: Spring Boot + LangChain4j (PRESERVED)
│   └── build.gradle (pure Java, no ForgeGradle)
├── fabric-mod/    # NEW: Fabric wrapper
│   ├── build.gradle (Fabric Loom + core dependency)
│   └── src/main/java/com/mcagent/fabric/
└── settings.gradle
```

**Rationale:** Fabric uses Fabric Loom (Gradle plugin), not ForgeGradle. We CANNOT use ForgeGradle for Fabric. The cleanest approach is a Gradle multi-module project where `core` is pure Java and `fabric-mod` is a Fabric mod that depends on `core`.

---

## Phase 1: Core Module Extraction (Day 2-3)

### 1.1 Extract Core from Current Build
The existing `core/` package is already pure Java with no Minecraft imports. Extract it into its own Gradle subproject.

**Files to move:**
- All files under `src/main/java/com/mcagent/core/`
- All files under `src/test/java/com/mcagent/core/`
- `src/main/resources/application.yml`
- H2 database dependencies
- Spring Boot dependencies
- LangChain4j dependencies

**Files to LEAVE in Forge module (or remove):**
- `src/main/java/com/mcagent/mod/` (Forge-specific wrapper)
- `src/main/resources/META-INF/mods.toml` (Forge mod descriptor)
- `src/main/resources/META-INF/accesstransformer.cfg` (Forge-specific)
- ForgeGradle plugin configuration

### 1.2 Core build.gradle (NEW)
```groovy
plugins {
    id 'java'
    id 'java-library'
    id 'maven-publish'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Spring Boot (headless, no web)
    implementation platform('org.springframework.boot:spring-boot-dependencies:3.4.0')
    implementation('org.springframework.boot:spring-boot-starter') {
        exclude group: 'ch.qos.logback'
        exclude group: 'org.apache.logging.log4j', module: 'log4j-to-slf4j'
    }
    implementation('org.springframework.boot:spring-boot-starter-data-jpa') {
        exclude group: 'ch.qos.logback'
        exclude group: 'org.apache.logging.log4j', module: 'log4j-to-slf4j'
    }
    
    // LangChain4j
    implementation platform('dev.langchain4j:langchain4j-bom:1.15.0')
    implementation 'dev.langchain4j:langchain4j'
    implementation 'dev.langchain4j:langchain4j-open-ai'
    implementation 'dev.langchain4j:langchain4j-chroma'
    
    // Database
    runtimeOnly 'com.h2database:h2:2.3.232'
    
    // Utilities
    implementation 'com.google.guava:guava:33.5.0-jre'
    implementation 'org.apache.httpcomponents.client5:httpclient5:5.4.1'
    
    // Lombok
    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'
    
    // Testing
    testImplementation 'org.springframework.boot:spring-boot-starter-test:3.4.0'
}
```

### 1.3 Verify Core Builds Independently
```bash
./gradlew :core:build
./gradlew :core:test
```
All 22 existing tests should pass.

### 1.4 Core Application Bootstrap
The `CoreApplication.java` currently uses `@Import` to load beans. Ensure it can be started headless (no web server) and that `application.yml` is on the classpath.

**Key config in application.yml:**
```yaml
spring:
  main:
    web-application-type: none
    banner-mode: off
```

---

## Phase 2: Baritone 26.1 Fabric Fork (Day 3-4)

### 2.1 Clone and Build Baritone Fork
```bash
# Clone the 26.1 fork
git clone -b 26.1 https://github.com/fnltochka/baritone.git baritone-26.1
cd baritone-26.1

# Set Java 25
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home

# Build Fabric version
./gradlew :fabric:build

# The output JAR will be at:
# fabric/build/libs/baritone-standalone-fabric-[version].jar
```

### 2.2 Baritone API Dependency
We need Baritone's API JAR for compilation (compile-only, not bundled).

**Option A:** Use the API JAR from the fork build
```bash
# After building, find the API JAR
find baritone-26.1 -name "baritone-api*.jar" | grep -v sources
```

**Option B:** Add as a `compileOnly` dependency pointing to the locally built JAR

**In fabric-mod/build.gradle:**
```groovy
dependencies {
    // Baritone API (compile-only, runtime provided by standalone mod)
    compileOnly files('../baritone-26.1/fabric/build/libs/baritone-api-fabric-[version].jar')
    
    // Minecraft + Fabric
    minecraft "com.mojang:minecraft:26.1.2"
    mappings "net.fabricmc:yarn:26.1.2+build.x:v2"
    modImplementation "net.fabricmc:fabric-loader:0.16.x"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.x.y+26.1.2"
}
```

### 2.3 Install Baritone in Minecraft
```bash
# Copy standalone Baritone mod to mods folder
cp baritone-26.1/fabric/build/libs/baritone-standalone-fabric-[version].jar \
  ~/Library/Application\ Support/minecraft/mods/
```

Verify Baritone loads in vanilla 26.1.2 Fabric client (no McAgent yet).

---

## Phase 3: Fabric Mod Wrapper (Day 4-8)

### 3.1 Fabric Loom Setup
**fabric-mod/build.gradle:**
```groovy
plugins {
    id 'fabric-loom' version '1.7-SNAPSHOT'
    id 'maven-publish'
}

version = '0.2.0-SNAPSHOT'
group = 'com.mcagent'

base {
    archivesName = 'mc-agent-fabric'
}

repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
    maven { url = 'https://maven.fabricmc.net/' }
    flatDir {
        dirs '../.local-deps'
    }
}

dependencies {
    // Minecraft + Fabric
    minecraft "com.mojang:minecraft:26.1.2"
    mappings "net.fabricmc:yarn:26.1.2+build.x:v2"
    modImplementation "net.fabricmc:fabric-loader:0.16.x"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.x.y+26.1.2"
    
    // Baritone API (compile-only)
    compileOnly name: 'baritone-api-fabric-[version]'
    
    // Core module
    implementation project(':core')
    
    // Need to include core's transitive dependencies in the mod JAR
    // Use Shadow or JiJ (Jar-in-Jar) for this
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}
```

### 3.2 Fabric Mod Entry Point
**New file:** `fabric-mod/src/main/java/com/mcagent/fabric/McAgentFabricMod.java`

Fabric uses interfaces for entry points instead of Forge's `@Mod` annotation.

```java
package com.mcagent.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McAgentFabricMod implements ModInitializer {
    public static final String MOD_ID = "mc_agent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static FabricChatHandler chatHandler;
    private static FabricBaritoneBridge baritoneBridge;
    
    @Override
    public void onInitialize() {
        LOGGER.info("McAgent Fabric mod initializing...");
        
        // Bootstrap Spring context from core module
        // This must happen BEFORE registering event handlers
        FabricSpringBootstrap.init();
        
        // Initialize Baritone bridge
        baritoneBridge = new FabricBaritoneBridge();
        
        // Initialize chat handler
        chatHandler = new FabricChatHandler(baritoneBridge);
        
        // Register Fabric event handlers
        registerEventHandlers();
        
        LOGGER.info("McAgent Fabric mod initialized.");
    }
    
    private void registerEventHandlers() {
        // Chat message received
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                chatHandler.onChatMessage(message.getString());
            }
        });
        
        // Client tick (for Baritone progress callbacks)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            chatHandler.onClientTick();
        });
        
        // Player joined server (reset context)
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            chatHandler.onPlayerJoined();
        });
    }
    
    public static FabricChatHandler getChatHandler() {
        return chatHandler;
    }
    
    public static FabricBaritoneBridge getBaritoneBridge() {
        return baritoneBridge;
    }
}
```

### 3.3 Fabric Mod Descriptor
**New file:** `fabric-mod/src/main/resources/fabric.mod.json`

```json
{
  "schemaVersion": 1,
  "id": "mc_agent",
  "version": "${version}",
  "name": "McAgent",
  "description": "LLM-powered autonomous Minecraft agent for Fabric 26.1.2",
  "authors": ["McAgent Team"],
  "contact": {},
  "license": "MIT",
  "icon": "assets/mc_agent/icon.png",
  "environment": "client",
  "entrypoints": {
    "client": [
      "com.mcagent.fabric.McAgentFabricMod"
    ]
  },
  "depends": {
    "fabricloader": ">=0.16.0",
    "fabric-api": "*",
    "minecraft": ">=26.1.2",
    "java": ">=25",
    "baritone": ">=1.15.0"
  },
  "suggests": {
    "modmenu": "*"
  }
}
```

### 3.4 Spring Context Bootstrap for Fabric
**New file:** `fabric-mod/src/main/java/com/mcagent/fabric/FabricSpringBootstrap.java`

This replaces `ModSpringConfig.java` from the Forge version.

```java
package com.mcagent.fabric;

import com.mcagent.core.CoreApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public class FabricSpringBootstrap {
    private static ConfigurableApplicationContext springContext;
    
    public static void init() {
        if (springContext == null) {
            // Set system properties before Spring boots
            String apiKey = System.getenv("FIREWORKS_API_KEY");
            if (apiKey != null) {
                System.setProperty("FIREWORKS_API_KEY", apiKey);
            }
            
            // Start Spring Boot headless application
            springContext = SpringApplication.run(CoreApplication.class);
            
            McAgentFabricMod.LOGGER.info("Spring Boot context started with {} beans", 
                springContext.getBeanDefinitionCount());
        }
    }
    
    public static ConfigurableApplicationContext getContext() {
        return springContext;
    }
    
    public static void shutdown() {
        if (springContext != null) {
            springContext.close();
            springContext = null;
        }
    }
}
```

### 3.5 Chat Event Handler (Fabric)
**New file:** `fabric-mod/src/main/java/com/mcagent/fabric/FabricChatHandler.java`

This replaces `ChatEventHandler.java` from the Forge version.

```java
package com.mcagent.fabric;

import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FabricChatHandler {
    private static final Pattern CHAT_PATTERN = Pattern.compile("^<(\\w+)>\\s*(.+)");
    private static final Pattern ALTERNATIVE_PATTERN = Pattern.compile("^(\\w+):\\s*(.+)");
    
    private final FabricBaritoneBridge baritoneBridge;
    private final LangChain4jService langChainService;
    private final ChatService chatService;
    
    public FabricChatHandler(FabricBaritoneBridge baritoneBridge) {
        this.baritoneBridge = baritoneBridge;
        this.langChainService = FabricSpringBootstrap.getContext()
            .getBean(LangChain4jService.class);
        this.chatService = FabricSpringBootstrap.getContext()
            .getBean(ChatService.class);
    }
    
    public void onChatMessage(String message) {
        // Extract player name and content
        String playerName = extractPlayerName(message);
        String content = extractContent(message);
        
        if (playerName == null || content == null) return;
        
        // Check if message is directed at bot
        if (!isBotMentioned(content)) return;
        
        // Strip bot name and trigger word
        String command = stripTriggerWords(content);
        
        // Process through LLM
        processCommand(playerName, command);
    }
    
    private String extractPlayerName(String message) {
        Matcher m = CHAT_PATTERN.matcher(message);
        if (m.matches()) return m.group(1);
        
        m = ALTERNATIVE_PATTERN.matcher(message);
        if (m.matches()) return m.group(1);
        
        return null;
    }
    
    private String extractContent(String message) {
        Matcher m = CHAT_PATTERN.matcher(message);
        if (m.matches()) return m.group(2);
        
        m = ALTERNATIVE_PATTERN.matcher(message);
        if (m.matches()) return m.group(2);
        
        return null;
    }
    
    private boolean isBotMentioned(String content) {
        String lower = content.toLowerCase();
        return lower.contains("bot") || lower.contains("agent") || lower.contains("mcagent");
    }
    
    private String stripTriggerWords(String content) {
        return content.replaceAll("(?i)\\b(bot|agent|hey\\s+bot|mcagent)\\b", "").trim();
    }
    
    private void processCommand(String playerName, String command) {
        try {
            // Use LangChain4j service to get response
            var response = langChainService.processCommand(playerName, command);
            
            // Send response to chat
            sendChatMessage(response.getMessage());
            
            // Execute any actions
            if (response.getAction() != null) {
                executeAction(response.getAction());
            }
        } catch (Exception e) {
            McAgentFabricMod.LOGGER.error("Error processing command", e);
            sendChatMessage("Sorry, I encountered an error processing that.");
        }
    }
    
    private void executeAction(BotAction action) {
        switch (action.getType()) {
            case NAVIGATE:
                baritoneBridge.navigateTo(action.getTargetX(), action.getTargetY(), action.getTargetZ());
                break;
            case MINE:
                baritoneBridge.mineBlocks(action.getBlockType(), action.getQuantity());
                break;
            case FOLLOW:
                baritoneBridge.followPlayer(action.getTargetPlayer());
                break;
            case REMEMBER:
                // Handled by LangChain4jService via tool calling
                break;
        }
    }
    
    public void sendChatMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.networkHandler.sendChatMessage(message);
        }
    }
    
    public void onClientTick() {
        // Check for Baritone progress callbacks
        baritoneBridge.checkProgress();
    }
    
    public void onPlayerJoined() {
        // Reset chat memory when joining a new server
        // (Implementation depends on LangChain4jService API)
    }
}
```

### 3.6 Baritone Bridge (Fabric)
**New file:** `fabric-mod/src/main/java/com/mcagent/fabric/FabricBaritoneBridge.java`

This replaces `BaritoneOperationsImpl.java` from the Forge version.

```java
package com.mcagent.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IMineProcess;
import baritone.api.process.IFollowProcess;
import net.minecraft.block.Block;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;

import java.util.function.Consumer;

public class FabricBaritoneBridge {
    private IBaritone baritone;
    private Consumer<String> progressCallback;
    
    public void init() {
        baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    }
    
    public void setProgressCallback(Consumer<String> callback) {
        this.progressCallback = callback;
    }
    
    public void navigateTo(int x, int y, int z) {
        if (baritone == null) return;
        
        ICustomGoalProcess process = baritone.getCustomGoalProcess();
        process.setGoalAndPath(new GoalBlock(new BlockPos(x, y, z)));
        
        if (progressCallback != null) {
            progressCallback.accept("<framework>Navigating to " + x + ", " + y + ", " + z + "</framework>");
        }
    }
    
    public void navigateToXZ(int x, int z) {
        if (baritone == null) return;
        
        ICustomGoalProcess process = baritone.getCustomGoalProcess();
        process.setGoalAndPath(new GoalXZ(x, z));
        
        if (progressCallback != null) {
            progressCallback.accept("<framework>Navigating to X:" + x + " Z:" + z + "</framework>");
        }
    }
    
    public void mineBlocks(String blockName, int quantity) {
        if (baritone == null) return;
        
        Identifier blockId = Identifier.tryParse(blockName);
        if (blockId == null) {
            blockId = Identifier.of("minecraft", blockName);
        }
        
        Block block = Registries.BLOCK.get(blockId);
        if (block == null) {
            if (progressCallback != null) {
                progressCallback.accept("<framework>Unknown block: " + blockName + "</framework>");
            }
            return;
        }
        
        IMineProcess mineProcess = baritone.getMineProcess();
        mineProcess.mine(quantity, block);
        
        if (progressCallback != null) {
            progressCallback.accept("<framework>Mining " + quantity + " " + blockName + "</framework>");
        }
    }
    
    public void followPlayer(String playerName) {
        if (baritone == null) return;
        
        IFollowProcess followProcess = baritone.getFollowProcess();
        followProcess.follow(entity -> entity.getName().getString().equals(playerName));
        
        if (progressCallback != null) {
            progressCallback.accept("<framework>Following player: " + playerName + "</framework>");
        }
    }
    
    public void stop() {
        if (baritone == null) return;
        baritone.getPathingBehavior().cancelEverything();
    }
    
    public void checkProgress() {
        // Called every client tick
        // Check if pathing/mining/following is complete
        // and report progress via callback
        if (baritone == null || progressCallback == null) return;
        
        // Baritone event listeners would be better than polling,
        // but Fabric event registration differs from Forge
        // This is a placeholder for the actual implementation
    }
}
```

### 3.7 Dependency Bundling (Critical)

The `core` module has many dependencies (Spring Boot, LangChain4j, H2, etc.). These must be included in the Fabric mod JAR. Options:

**Option A: Shadow Plugin (Recommended)**
Use the Shadow plugin in `fabric-mod/build.gradle` to bundle all dependencies from `core` into the mod JAR.

**Option B: JiJ (Jar-in-Jar)**
Fabric supports including dependency JARs inside the mod JAR under `META-INF/jars/`. Loom can automate this.

**Recommended approach:** Shadow with package relocation to avoid conflicts.

```groovy
// In fabric-mod/build.gradle
plugins {
    id 'com.gradleup.shadow' version '8.3.6'
}

shadowJar {
    archiveClassifier.set('all')
    configurations = [project.configurations.runtimeClasspath]
    
    // Relocate packages to avoid conflicts
    relocate 'org.springframework', 'com.mcagent.shaded.org.springframework'
    relocate 'dev.langchain4j', 'com.mcagent.shaded.dev.langchain4j'
    relocate 'com.fasterxml.jackson', 'com.mcagent.shaded.com.fasterxml.jackson'
    // ... (same relocations as current build)
}

// Fabric Loom will use the shadow JAR as the mod artifact
remapJar {
    dependsOn shadowJar
    inputFile = shadowJar.archiveFile
}
```

---

## Phase 4: Testing Strategy (Day 8-12)

### 4.1 Unit Tests (Core Module)
Run existing tests to confirm core logic is intact:
```bash
./gradlew :core:test
```

### 4.2 Integration Test: Standalone LLM
Create a test that boots Spring context without Minecraft:
```java
@Test
public void testSpringContextBoots() {
    var context = SpringApplication.run(CoreApplication.class);
    assertNotNull(context.getBean(LangChain4jService.class));
    assertNotNull(context.getBean(ChatService.class));
    context.close();
}
```

### 4.3 Fabric Dev Client Test
```bash
./gradlew :fabric-mod:runClient
```
This launches a Fabric dev client with the mod loaded.

### 4.4 End-to-End Test (Manual)
1. Build mod: `./gradlew :fabric-mod:shadowJar`
2. Install: `cp fabric-mod/build/libs/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar ~/Library/Application\ Support/minecraft/mods/`
3. Install Baritone: `cp baritone-standalone-fabric-26.1.jar ~/Library/Application\ Support/minecraft/mods/`
4. Launch Fabric 26.1.2 client
5. Join single-player world or server
6. Chat: `agent remember this as test location`
7. Chat: `agent go to test location`
8. Verify: LLM responds, Baritone pathing initiates

### 4.5 Logging & Debugging
Fabric uses SLF4J natively. Ensure our logging works:
- `McAgentFabricMod.LOGGER.info("message")` for mod-specific logs
- Spring Boot logs to `logs/` directory (configure in `application.yml`)
- Minecraft logs to `~/Library/Application Support/minecraft/logs/`

---

## Phase 5: Packaging & Distribution (Day 12-14)

### 5.1 Build Script
```bash
#!/bin/bash
set -e

# Set Java 25
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home

# Build core
./gradlew :core:build :core:test

# Build Fabric mod with shadow
./gradlew :fabric-mod:shadowJar

# Copy to Minecraft mods
MOD_JAR=$(ls fabric-mod/build/libs/*-all.jar | head -1)
cp "$MOD_JAR" ~/Library/Application\ Support/minecraft/mods/

echo "Mod installed: $MOD_JAR"
```

### 5.2 Distribution Checklist
- [ ] Mod JAR includes all dependencies (Shadow)
- [ ] `fabric.mod.json` has correct version and dependencies
- [ ] Baritone standalone Fabric JAR is listed as a dependency
- [ ] Java 25 requirement is documented
- [ ] API key setup instructions are clear

---

## Phase 6: Known Issues & Mitigations

### Issue 1: Shadow JAR Size
**Problem:** The mod JAR will be 100MB+ due to Spring Boot + LangChain4j.
**Mitigation:** Package relocation prevents conflicts but doesn't reduce size. Accept this as the cost of bundling an AI stack. Alternative: Use `provided` scope for some deps, but this increases user setup complexity.

### Issue 2: Baritone Fork Maintenance
**Problem:** We're using an unmerged fork (`fnltochka/baritone` 26.1 branch).
**Mitigation:**
- Pin to a specific commit hash
- Monitor PR #4990 for merge or updates
- Maintain our own fork if upstream stalls
- Document this dependency clearly for users

### Issue 3: Fabric vs Forge API Differences
**Problem:** No direct 1:1 mapping for all Forge events.
**Key differences:**
- Forge `ClientChatEvent` → Fabric `ClientReceiveMessageEvents.GAME`
- Forge `TickEvent.ClientTick` → Fabric `ClientTickEvents.END_CLIENT_TICK`
- Forge `@Mod` annotation → Fabric `fabric.mod.json` + `ModInitializer`
- Forge `IForgeRegistry` → Fabric `Registries` (vanilla registry)
- Forge `MinecraftForge.EVENT_BUS` → Fabric's event-specific registration

### Issue 4: Java 25 Module System
**Problem:** Java 25 has stricter module system enforcement.
**Mitigation:**
- Add `--add-opens` and `--add-exports` JVM args as needed
- Spring Boot 3.4 should handle most of this automatically
- Test thoroughly on Java 25, not just compile

### Issue 5: macOS ARM64 Native Libraries
**Problem:** M3 Mac may need specific native library handling.
**Mitigation:**
- Fabric Loader handles LWJGL natives automatically
- Ensure `lwjgl-3.4.1-natives-macos-arm64.jar` is on classpath
- Test on actual M3 hardware (we have this)

---

## Appendix A: File Mapping (Forge → Fabric)

| Forge File | Fabric Replacement | Notes |
|-----------|-------------------|-------|
| `MinecraftAgentMod.java` | `McAgentFabricMod.java` | Different initialization pattern |
| `ChatEventHandler.java` | `FabricChatHandler.java` | Different event registration |
| `BaritoneOperationsImpl.java` | `FabricBaritoneBridge.java` | Same Baritone API, different imports |
| `ModSpringConfig.java` | `FabricSpringBootstrap.java` | Spring context bootstrapping |
| `META-INF/mods.toml` | `fabric.mod.json` | Different format |
| `META-INF/accesstransformer.cfg` | N/A (not needed) | Fabric doesn't use ATs |
| `@SubscribeEvent` annotations | Fabric event registration | In `onInitialize()` method |
| `MinecraftForge.EVENT_BUS` | Fabric event interfaces | Direct registration in entry point |

---

## Appendix B: Dependency Versions for 26.1.2

| Dependency | Version | Notes |
|-----------|---------|-------|
| Minecraft | 26.1.2 | |
| Java | 25 | Zulu JDK recommended |
| Fabric Loader | 0.16.x+ | Check latest for 26.1.2 |
| Fabric API | 0.x.y+26.1.2 | Check latest on fabricmc.net |
| Yarn Mappings | 26.1.2+build.x | Use latest build number |
| Baritone | 1.15.0+ (fork) | Use fnltochka 26.1 branch |
| Spring Boot | 3.4.0 | Java 25 compatible |
| LangChain4j | 1.15.0 | Latest stable |
| H2 | 2.3.232 | Latest |
| Gradle | 8.14.5 | Current, compatible with Fabric Loom |

---

## Appendix C: API Key Configuration

The mod reads `FIREWORKS_API_KEY` from:
1. Environment variable (highest priority)
2. JVM system property: `-DFIREWORKS_API_KEY=fw_...`
3. `application.yml` (fallback, but should not hardcode)

**For launcher setup:**
```bash
# In launcher profile JVM arguments:
-DFIREWORKS_API_KEY=fw_S7hnN4sjQy6MwWfPr1hcGu
```

**For dev environment:**
```bash
export FIREWORKS_API_KEY=fw_S7hnN4sjQy6MwWfPr1hcGu
./gradlew :fabric-mod:runClient
```

---

## Appendix D: Contact & Resources

### Key URLs
- **Forge 26.1.2:** https://files.minecraftforge.net/net/minecraftforge/forge/index_26.1.2.html
- **Baritone PR #4990:** https://github.com/cabaletta/baritone/pull/4990
- **Baritone Fork:** https://github.com/fnltochka/baritone/tree/26.1
- **Fabric Wiki:** https://fabricmc.net/wiki/
- **Fabric API Javadocs:** https://maven.fabricmc.net/docs/fabric-api-0.x.y/
- **Yarn Mappings:** https://maven.fabricmc.net/docs/yarn-26.1.2+build.x/

### Team Notes
- This plan assumes the user has an M3 Mac running macOS 26.5
- Java 25 is non-negotiable for 26.1.2
- The Shadow JAR approach is proven (we used it for Forge 1.20.1)
- Baritone fork is the biggest risk - monitor PR #4990 daily

---

**Plan Version:** 1.0
**Ready for Execution:** YES
**Next Session Should Start With:** Phase 0.1 (Install Java 25)
