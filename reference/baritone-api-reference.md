# Baritone API Reference and Utilization Guide

## For Java Developers: Minecraft Bot Wrapper Implementation

---

**Version:** 1.10.x (Latest Stable)  
**Target Minecraft Versions:** 1.12.2 - 1.20.4  
**Last Updated:** May 2026

---

## Table of Contents

1. [Baritone Overview](#1-baritone-overview)
2. [Integration Methods](#2-integration-methods)
3. [Core API Classes](#3-core-api-classes)
4. [Command Mapping Reference](#4-command-mapping-reference)
5. [Event Handling](#5-event-handling)
6. [Settings and Configuration](#6-settings-and-configuration)
7. [Error Handling and Edge Cases](#7-error-handling-and-edge-cases)
8. [Best Practices](#8-best-practices)
9. [Troubleshooting Guide](#9-troubleshooting-guide)

---

## 1. Baritone Overview

### What Is Baritone?

Baritone is an **autonomous pathfinding system** for Minecraft that operates at the client level. Originally developed by leijurv and maintained by Cabaletta, it provides sophisticated A*-based pathfinding with awareness of:

- Terrain topology and obstacles
- Block breakability and placement requirements
- Lava, water, and fall damage avoidance
- Tool selection and inventory management
- Real-time path recalculation

### Core Capabilities

| Capability | Description | API Class |
|------------|-------------|-----------|
| **Pathfinding** | A* pathfinding with JPS (Jump Point Search) optimization | `PathingBehavior` |
| **Mining** | Automated resource gathering with vein detection | `MineBehavior` |
| **Building** | Schematic-based construction and block placement | `BuilderBehavior` |
| **Following** | Entity tracking and pursuit | `FollowBehavior` |
| **Inventory** | Automatic tool selection and item management | `InventoryBehavior` |
| **Combat** | Basic mob avoidance and safety systems | `CombatBehavior` |

### Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    BaritoneAPI                          │
│              (Singleton Entry Point)                    │
└────────────────────┬────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
┌──────────────┐ ┌────────┐ ┌─────────────┐
│ IBaritone    │ │Settings│ │Event Bus    │
│ (Instance)   │ │(Config)│ │(Observers)  │
└──────┬───────┘ └────────┘ └─────────────┘
       │
       ▼
┌─────────────────────────────────────────┐
│          Behavior Controllers           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │ Pathing  │ │  Mine    │ │  Build   │ │
│  │Behavior  │ │Behavior  │ │Behavior  │ │
│  └──────────┘ └──────────┘ └──────────┘ │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │ Follow   │ │Inventory│ │  Combat  │ │
│  │Behavior  │ │Behavior  │ │Behavior  │ │
│  └──────────┘ └──────────┘ └──────────┘ │
└─────────────────────────────────────────┘
```

### Limitations and Constraints

**Technical Limitations:**
- Client-side only (no server-side pathfinding)
- Requires chunk loading (cannot path through unloaded chunks)
- Single-threaded pathfinding (may cause frame drops on complex paths)
- Limited to vanilla Minecraft physics (no fly hacks in standard build)

**Behavioral Limitations:**
- Cannot solve puzzles requiring redstone logic
- Limited understanding of mob AI patterns
- May struggle with modded blocks without custom cost functions
- No natural language processing (commands must be structured)

**Safety Limitations:**
- Lava avoidance is heuristic-based, not guaranteed
- Fall damage calculations assume vanilla physics
- May not account for server lag or latency

---

## 2. Integration Methods

### 2.1 Dependency Setup

#### Maven Configuration

```xml
<!-- Add JitPack repository -->
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<!-- Baritone API dependency (unmapped) -->
<dependency>
    <groupId>com.github.cabaletta</groupId>
    <artifactId>baritone</artifactId>
    <version>v1.10.1</version>
    <classifier>api-unoptimized</classifier>
</dependency>

<!-- For mapped (obfuscated) versions -->
<dependency>
    <groupId>com.github.cabaletta</groupId>
    <artifactId>baritone</artifactId>
    <version>v1.10.1</version>
    <classifier>api</classifier>
</dependency>
```

#### Gradle Configuration

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    // API only (for bot wrappers)
    implementation 'com.github.cabaletta:baritone:v1.10.1:api-unoptimized'
    
    // OR mapped version for specific MC version
    implementation 'com.github.cabaletta:baritone:v1.10.1:api'
}
```

#### Version Matrix

| Baritone Version | Minecraft Version | Mapping | Notes |
|------------------|-------------------|---------|-------|
| v1.10.1 | 1.20.4 | Intermediary | Latest stable |
| v1.9.4 | 1.19.4 | Intermediary | Legacy support |
| v1.8.5 | 1.18.2 | Intermediary | Legacy support |
| v1.7.2 | 1.17.1 | Intermediary | Legacy support |
| v1.6.5 | 1.16.5 | MCP/Official | Legacy support |
| v1.5.3 | 1.15.2 | MCP | Legacy support |
| v1.4.6 | 1.14.4 | MCP | Legacy support |
| v1.2.15 | 1.12.2 | MCP | Legacy support |

### 2.2 API Initialization

#### Basic Initialization Pattern

```java
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;

public class BaritoneWrapper {
    
    private IBaritone baritone;
    private Settings settings;
    
    public void initialize() {
        // Get the primary baritone instance
        // In a standalone client, this is typically pre-initialized
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        
        // Access settings
        this.settings = BaritoneAPI.getSettings();
        
        // Configure before use
        configureSettings();
    }
    
    private void configureSettings() {
        // Critical safety settings
        settings.allowBreak.value = true;
        settings.allowPlace.value = true;
        settings.allowSprint.value = true;
        settings.allowParkour.value = true;
        
        // Pathfinding limits
        settings.pathingMaxChunkBorderFetch.value = 50;
        settings.primaryTimeoutMS.value = 5000L;
        settings.failureTimeoutMS.value = 2000L;
        
        // Safety margins
        settings.blockReachDistance.value = 4.5f;
        settings.followRadius.value = 32.0;
    }
}
```

#### Multi-Instance Management (Advanced)

```java
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MultiBotManager {
    
    private final IBaritoneProvider provider;
    private final Map<String, IBaritone> botInstances;
    
    public MultiBotManager() {
        this.provider = BaritoneAPI.getProvider();
        this.botInstances = new ConcurrentHashMap<>();
    }
    
    /**
     * Register a new bot instance with custom settings
     */
    public IBaritone registerBot(String botId, BotConfiguration config) {
        // Note: Multiple baritone instances require custom provider setup
        // This is typically done through Baritone's internal factory
        
        IBaritone baritone = createBaritoneInstance(config);
        botInstances.put(botId, baritone);
        
        // Apply bot-specific settings
        applyConfiguration(baritone, config);
        
        return baritone;
    }
    
    private IBaritone createBaritoneInstance(BotConfiguration config) {
        // Factory method depends on your client implementation
        // For Fabric/Forge mods, use the platform-specific provider
        return provider.getPrimaryBaritone(); // Simplified - see advanced section
    }
    
    private void applyConfiguration(IBaritone baritone, BotConfiguration config) {
        Settings settings = baritone.getSettings();
        
        // Apply configuration
        settings.allowBreak.value = config.canBreakBlocks();
        settings.allowPlace.value = config.canPlaceBlocks();
        settings.allowParkour.value = config.allowParkour();
        settings.allowSprint.value = config.allowSprint();
        
        // Pathfinding configuration
        settings.pathingMaxChunkBorderFetch.value = config.getChunkFetchRadius();
        settings.primaryTimeoutMS.value = config.getPathfindingTimeout();
    }
    
    public IBaritone getBot(String botId) {
        return botInstances.get(botId);
    }
    
    public void shutdownBot(String botId) {
        IBaritone baritone = botInstances.remove(botId);
        if (baritone != null) {
            // Stop all behaviors
            baritone.getPathingBehavior().cancelEverything();
            baritone.getMineBehavior().cancel();
            baritone.getFollowBehavior().cancel();
        }
    }
}
```

### 2.3 Integration with Mod Loaders

#### Fabric Integration

```java
import net.fabricmc.api.ModInitializer;
import baritone.api.BaritoneAPI;

public class MyBotMod implements ModInitializer {
    
    @Override
    public void onInitialize() {
        // Baritone auto-initializes with Fabric
        // Wait for client initialization
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            initializeBaritoneIntegration();
        });
    }
    
    private void initializeBaritoneIntegration() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        
        // Register custom command handlers
        registerCustomCommands(baritone);
        
        // Setup event listeners
        setupEventHandlers(baritone);
    }
}
```

#### Forge Integration

```java
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import baritone.api.BaritoneAPI;

@Mod("mybotmod")
public class MyBotMod {
    
    public MyBotMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }
    
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level().isClientSide()) {
            initializeBaritone();
        }
    }
    
    private void initializeBaritone() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        // Configure and setup...
    }
}
```

---

## 3. Core API Classes

### 3.1 BaritoneAPI Provider

The `BaritoneAPI` class is the **singleton entry point** for all Baritone operations.

```java
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.Settings;

public class BaritoneProviderExample {
    
    public void demonstrateProvider() {
        // Get the provider singleton
        IBaritoneProvider provider = BaritoneAPI.getProvider();
        
        // Get primary instance (most common use case)
        IBaritone primary = provider.getPrimaryBaritone();
        
        // Get all registered baritone instances
        List<IBaritone> allBaritones = provider.getAllBaritones();
        
        // Access global settings
        Settings globalSettings = BaritoneAPI.getSettings();
        
        // Settings are shared across instances by default
        // Changes affect all baritone instances
    }
}
```

**Key Methods:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getProvider()` | `IBaritoneProvider` | Returns the singleton provider |
| `getSettings()` | `Settings` | Returns global settings object |
| `getVersion()` | `String` | Returns Baritone version string |

### 3.2 PathingBehavior

Controls movement, pathfinding, and navigation.

```java
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.calc.IPathingControlManager;

public class PathingExample {
    
    private IPathingBehavior pathing;
    
    public PathingExample(IBaritone baritone) {
        this.pathing = baritone.getPathingBehavior();
    }
    
    /**
     * Navigate to specific block coordinates
     */
    public void gotoBlock(int x, int y, int z) {
        Goal goal = new GoalBlock(x, y, z);
        pathing.setGoal(goal);
        pathing.pathToGoal();
    }
    
    /**
     * Navigate to X,Z coordinates (any Y level)
     */
    public void gotoSurface(int x, int z) {
        Goal goal = new GoalXZ(x, z);
        pathing.setGoal(goal);
        pathing.pathToGoal();
    }
    
    /**
     * Navigate to specific Y level (for mining)
     */
    public void gotoYLevel(int y) {
        Goal goal = new GoalYLevel(y);
        pathing.setGoal(goal);
        pathing.pathToGoal();
    }
    
    /**
     * Complex goal: Any of multiple targets
     */
    public void gotoNearest(List<BlockPos> targets) {
        Goal[] goals = targets.stream()
            .map(pos -> new GoalBlock(pos.getX(), pos.getY(), pos.getZ()))
            .toArray(Goal[]::new);
        
        Goal composite = new GoalComposite(goals);
        pathing.setGoal(composite);
        pathing.pathToGoal();
    }
    
    /**
     * Check pathing status
     */
    public PathingStatus getStatus() {
        return new PathingStatus(
            pathing.isPathing(),           // Currently moving?
            pathing.hasPath(),             // Has a calculated path?
            pathing.getCurrent(),          // Current path object
            pathing.getGoal(),             // Current goal
            pathing.getPathingControlManager().getInControl() != null
        );
    }
    
    /**
     * Force cancel all pathing
     */
    public void stop() {
        pathing.cancelEverything();
        pathing.setGoal(null);
    }
    
    /**
     * Pause without clearing goal
     */
    public void pause() {
        pathing.pause();
    }
    
    /**
     * Resume from pause
     */
    public void resume() {
        pathing.resume();
    }
}
```

**Goal Types Reference:**

| Goal Class | Description | Use Case |
|------------|-------------|----------|
| `GoalBlock` | Specific X,Y,Z coordinate | Precise positioning |
| `GoalXZ` | Specific X,Z (any Y) | Surface navigation |
| `GoalYLevel` | Specific Y level (any X,Z) | Mining at specific depth |
| `GoalTwoBlocks` | Two-block space (for standing) | Doorway navigation |
| `GoalGetToBlock` | Touching specific block | Chest access, button pressing |
| `GoalNear` | Within radius of coordinate | Area exploration |
| `GoalAxis` | On specific X OR Z axis | Corridor following |
| `GoalComposite` | Any of multiple goals | Multiple valid destinations |
| `GoalInverted` | Away from coordinate | Fleeing, exploration |
| `GoalStrictDirection` | Must approach from specific direction | Controlled entry |

### 3.3 MiningBehavior

Controls automated resource gathering.

```java
import baritone.api.behavior.IMineBehavior;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import java.util.ArrayList;

public class MiningExample {
    
    private IMineBehavior mining;
    private Settings settings;
    
    public MiningExample(IBaritone baritone) {
        this.mining = baritone.getMineBehavior();
        this.settings = baritone.getSettings();
    }
    
    /**
     * Mine specific ore
     */
    public void mineOre(Block oreBlock, int quantity) {
        BlockOptionalMeta bom = new BlockOptionalMeta(oreBlock);
        mining.mine(quantity, bom);
    }
    
    /**
     * Mine multiple ore types
     */
    public void mineOres(List<Block> oreBlocks, int quantityPerType) {
        BlockOptionalMetaLookup lookup = new BlockOptionalMetaLookup(
            oreBlocks.stream()
                .map(BlockOptionalMeta::new)
                .toArray(BlockOptionalMeta[]::new)
        );
        mining.mine(quantityPerType, lookup);
    }
    
    /**
     * Strip mining configuration
     */
    public void startStripMining() {
        // Configure for efficient strip mining
        settings.mineScanDroppedItems.value = true;
        settings.mineDropLoiterDurationMSThanksLouca.value = 250;
        settings.mineGoalUpdateInterval.value = 5;
        settings.mineMaxOreLocations.value = 1000;
        
        // Mine diamond ore
        BlockOptionalMeta diamond = new BlockOptionalMeta(Blocks.DIAMOND_ORE);
        mining.mine(0, diamond); // 0 = unlimited
    }
    
    /**
     * Branch mining at specific Y level
     */
    public void startBranchMining(int yLevel) {
        // First path to desired Y level
        pathing.setGoal(new GoalYLevel(yLevel));
        pathing.pathToGoal();
        
        // Wait for arrival, then start mining
        // (See event handling section for completion detection)
    }
    
    /**
     * Mine with vein following
     */
    public void mineWithVeinFollowing() {
        settings.mineFollowRadius.value = 10; // Follow veins up to 10 blocks
        settings.mineMaxOreLocations.value = 100;
        
        BlockOptionalMeta redstone = new BlockOptionalMeta(Blocks.REDSTONE_ORE);
        mining.mine(64, redstone); // Mine 64 redstone
    }
    
    /**
     * Stop mining
     */
    public void stopMining() {
        mining.cancel();
    }
    
    /**
     * Resume previous mining task
     */
    public void resumeMining() {
        mining.resume();
    }
    
    /**
     * Get mining status
     */
    public MiningStatus getStatus() {
        return new MiningStatus(
            mining.isMining(),
            mining.getCurrentTarget(),
            mining.getOreLocationsCount()
        );
    }
}
```

**Mining Settings:**

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `mineScanDroppedItems` | Boolean | true | Pick up dropped items while mining |
| `mineDropLoiterDurationMS` | Long | 250 | Time to wait for drops |
| `mineGoalUpdateInterval` | Integer | 5 | Ticks between goal updates |
| `mineMaxOreLocations` | Integer | 1000 | Maximum ore locations to track |
| `mineFollowRadius` | Integer | 0 | Radius to follow ore veins (0=disabled) |
| `mineMinimumPathLength` | Integer | 10 | Minimum path before considering |
| `mineMaxCachedOreLocations` | Integer | 10000 | Cache size for ore locations |

### 3.4 FollowBehavior

Controls entity following and pursuit.

```java
import baritone.api.behavior.IFollowBehavior;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.Animal;
import java.util.UUID;

public class FollowExample {
    
    private IFollowBehavior follow;
    private Settings settings;
    
    public FollowExample(IBaritone baritone) {
        this.follow = baritone.getFollowBehavior();
        this.settings = baritone.getSettings();
    }
    
    /**
     * Follow specific entity
     */
    public void followEntity(Entity target) {
        settings.followRadius.value = 5.0; // Stay within 5 blocks
        settings.followOffsetDirection.value = Direction.NORTH;
        settings.followOffsetDistance.value = 2.0;
        
        follow.follow(target);
    }
    
    /**
     * Follow player by name
     */
    public void followPlayer(String playerName) {
        settings.followRadius.value = 10.0;
        follow.follow(playerName);
    }
    
    /**
     * Follow with leash-like behavior
     */
    public void followLeashStyle(Entity target, double maxDistance) {
        settings.followRadius.value = maxDistance;
        settings.followOffsetDirection.value = null; // No offset
        settings.followOffsetDistance.value = 0.0;
        
        follow.follow(target);
    }
    
    /**
     * Formation following (offset position)
     */
    public void followInFormation(Entity target, Direction offsetDir, double offsetDist) {
        settings.followRadius.value = 32.0; // Large radius for formation
        settings.followOffsetDirection.value = offsetDir;
        settings.followOffsetDistance.value = offsetDist;
        
        follow.follow(target);
    }
    
    /**
     * Stop following
     */
    public void stopFollowing() {
        follow.cancel();
    }
    
    /**
     * Get follow status
     */
    public FollowStatus getStatus() {
        return new FollowStatus(
            follow.isFollowing(),
            follow.getFollowingEntity(),
            follow.getDistanceToTarget()
        );
    }
}
```

**Follow Settings:**

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `followRadius` | Double | 32.0 | Maximum distance to maintain |
| `followOffsetDirection` | Direction | null | Direction offset from target |
| `followOffsetDistance` | Double | 0.0 | Distance offset from target |

### 3.5 CommandManager

Handles command execution and registration.

```java
import baritone.api.command.manager.ICommandManager;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

public class CommandExample {
    
    private ICommandManager commands;
    
    public CommandExample(IBaritone baritone) {
        this.commands = baritone.getCommandManager();
    }
    
    /**
     * Execute a command by string
     */
    public void executeCommand(String commandString) {
        // Commands are prefixed with # in chat
        commands.execute(commandString);
    }
    
    /**
     * Register custom command
     */
    public void registerCustomCommand() {
        Command myCommand = new Command("mycommand") {
            @Override
            public void execute(String label, IArgConsumer args) throws CommandException {
                // Command implementation
                String arg = args.getString();
                logDirect("Custom command executed with: " + arg);
            }
            
            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }
            
            @Override
            public String getShortDesc() {
                return "My custom command description";
            }
            
            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                    "Detailed description line 1",
                    "Detailed description line 2"
                );
            }
        };
        
        commands.registerCommand(myCommand);
    }
    
    /**
     * Execute with arguments
     */
    public void executeGoto(int x, int y, int z) {
        commands.execute(String.format("goto %d %d %d", x, y, z));
    }
    
    /**
     * Execute mine command
     */
    public void executeMine(String blockName, int quantity) {
        commands.execute(String.format("mine %d %s", quantity, blockName));
    }
}
```

---

## 4. Command Mapping Reference

### 4.1 Chat Commands to API Calls

| Chat Command | API Equivalent | Parameters |
|--------------|----------------|------------|
| `#goto x y z` | `pathing.setGoal(new GoalBlock(x,y,z))` | Coordinates |
| `#goto x z` | `pathing.setGoal(new GoalXZ(x,z))` | Surface coords |
| `#mine 64 diamond_ore` | `mining.mine(64, new BlockOptionalMeta(Blocks.DIAMOND_ORE))` | Quantity, Block |
| `#follow playername` | `follow.follow("playername")` | Player name |
| `#stop` | `pathing.cancelEverything()` | None |
| `#pause` | `pathing.pause()` | None |
| `#resume` | `pathing.resume()` | None |
| `#build` | `builder.build(...)` | Schematic data |

### 4.2 Common Operations with Code Examples

#### Goto Operations

```java
public class GotoOperations {
    
    private final IPathingBehavior pathing;
    
    // Simple coordinate goto
    public void gotoCoordinates(int x, int y, int z) {
        GoalBlock goal = new GoalBlock(x, y, z);
        pathing.setGoal(goal);
        pathing.pathToGoal();
    }
    
    // Goto with timeout
    public void gotoWithTimeout(int x, int y, int z, long timeoutMs) {
        GoalBlock goal = new GoalBlock(x, y, z);
        pathing.setGoal(goal);
        
        // Set temporary timeout
        Long originalTimeout = settings.primaryTimeoutMS.value;
        settings.primaryTimeoutMS.value = timeoutMs;
        
        pathing.pathToGoal();
        
        // Restore timeout (in callback or separate thread)
        // settings.primaryTimeoutMS.value = originalTimeout;
    }
    
    // Goto with arrival callback
    public void gotoWithCallback(int x, int y, int z, Runnable onArrival) {
        GoalBlock goal = new GoalBlock(x, y, z);
        pathing.setGoal(goal);
        
        // Register one-time listener
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler()
            .registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    if (event == PathEvent.ARRIVED) {
                        onArrival.run();
                        // Unregister after execution
                    }
                }
            });
        
        pathing.pathToGoal();
    }
}
```

#### Mine Operations

```java
public class MineOperations {
    
    private final IMineBehavior mining;
    private final Settings settings;
    
    // Mine single block type
    public void mineBlock(Block block, int quantity) {
        BlockOptionalMeta bom = new BlockOptionalMeta(block);
        mining.mine(quantity, bom);
    }
    
    // Mine multiple block types
    public void mineBlocks(List<Block> blocks, int quantityEach) {
        BlockOptionalMeta[] boms = blocks.stream()
            .map(BlockOptionalMeta::new)
            .toArray(BlockOptionalMeta[]::new);
        
        BlockOptionalMetaLookup lookup = new BlockOptionalMetaLookup(boms);
        mining.mine(quantityEach, lookup);
    }
    
    // Mine with tool requirements
    public void mineWithToolCheck(Block block, int quantity, Item requiredTool) {
        // Check tool availability before mining
        if (!hasTool(requiredTool)) {
            throw new IllegalStateException("Required tool not available: " + requiredTool);
        }
        
        // Configure to respect tool requirements
        settings.mineToolRequirement.value = true;
        
        mineBlock(block, quantity);
    }
    
    // Smart mining: prioritize by value
    public void minePrioritized(Map<Block, Integer> blockPriorities) {
        // Sort by priority
        List<Map.Entry<Block, Integer>> sorted = blockPriorities.entrySet()
            .stream()
            .sorted(Map.Entry.<Block, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());
        
        // Mine highest priority first
        for (Map.Entry<Block, Integer> entry : sorted) {
            if (mining.isMining()) {
                break; // Already mining something
            }
            mineBlock(entry.getKey(), 64);
        }
    }
}
```

#### Build Operations

```java
import baritone.api.behavior.IBuilderBehavior;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.CompositeSchematic;

public class BuildOperations {
    
    private final IBuilderBehavior builder;
    
    // Build from schematic file
    public void buildSchematic(String schematicPath, BlockPos origin) {
        try {
            ISchematic schematic = SchematicSystem.INSTANCE.load(schematicPath);
            builder.build(schematic, origin);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load schematic: " + e.getMessage(), e);
        }
    }
    
    // Build simple filled area
    public void buildFilledArea(BlockPos corner1, BlockPos corner2, Block fillBlock) {
        int width = Math.abs(corner2.getX() - corner1.getX()) + 1;
        int height = Math.abs(corner2.getY() - corner1.getY()) + 1;
        int length = Math.abs(corner2.getZ() - corner1.getZ()) + 1;
        
        ISchematic schematic = new FillSchematic(width, height, length, fillBlock);
        builder.build(schematic, corner1);
    }
    
    // Build with material verification
    public void buildWithMaterialsCheck(ISchematic schematic, BlockPos origin, 
                                        Map<Block, Integer> requiredMaterials) {
        // Verify materials available
        for (Map.Entry<Block, Integer> material : requiredMaterials.entrySet()) {
            if (!hasMaterial(material.getKey(), material.getValue())) {
                throw new IllegalStateException(
                    "Insufficient " + material.getKey() + 
                    ": need " + material.getValue()
                );
            }
        }
        
        builder.build(schematic, origin);
    }
}
```

### 4.3 Parameter Types and Validation

```java
public class ParameterValidation {
    
    /**
     * Validate coordinates are within world bounds
     */
    public static boolean validateCoordinates(int x, int y, int z) {
        return y >= -64 && y <= 320 && // World height limits
               Math.abs(x) <= 30000000 && // World border
               Math.abs(z) <= 30000000;
    }
    
    /**
     * Validate block name is valid
     */
    public static Optional<Block> validateBlockName(String blockName) {
        ResourceLocation location = new ResourceLocation(blockName);
        Block block = ForgeRegistries.BLOCKS.getValue(location);
        
        if (block == null || block == Blocks.AIR) {
            return Optional.empty();
        }
        return Optional.of(block);
    }
    
    /**
     * Validate quantity is reasonable
     */
    public static boolean validateQuantity(int quantity) {
        return quantity >= 0 && quantity <= 1000000; // Sanity check
    }
    
    /**
     * Parse and validate goto command arguments
     */
    public static Goal parseGotoArguments(String[] args) throws IllegalArgumentException {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: goto <x> <z> [y]");
        }
        
        try {
            int x = Integer.parseInt(args[0]);
            int z = Integer.parseInt(args[1]);
            
            if (args.length == 2) {
                return new GoalXZ(x, z);
            } else {
                int y = Integer.parseInt(args[2]);
                if (!validateCoordinates(x, y, z)) {
                    throw new IllegalArgumentException("Coordinates out of bounds");
                }
                return new GoalBlock(x, y, z);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format");
        }
    }
}
```

---

## 5. Event Handling

### 5.1 Path Completion Callbacks

```java
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;

public class EventHandlingExample {
    
    private final IBaritone baritone;
    private final IGameEventHandler events;
    
    public EventHandlingExample(IBaritone baritone) {
        this.baritone = baritone;
        this.events = baritone.getGameEventHandler();
    }
    
    /**
     * Register path completion listener
     */
    public void registerPathCompletionListener(Runnable onComplete) {
        events.registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                switch (event) {
                    case ARRIVED:
                        System.out.println("Path completed successfully!");
                        onComplete.run();
                        break;
                    case CANCELED:
                        System.out.println("Path was canceled");
                        break;
                    case CALCULATION_COMPLETE:
                        System.out.println("Path calculation finished");
                        break;
                }
            }
        });
    }
    
    /**
     * One-shot completion listener (auto-unregisters)
     */
    public void registerOneShotListener(Runnable onArrival) {
        AbstractGameEventListener listener = new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                if (event == PathEvent.ARRIVED) {
                    onArrival.run();
                    events.unregisterEventListener(this);
                }
            }
        };
        events.registerEventListener(listener);
    }
}
```

**PathEvent Types:**

| Event | Trigger | Use Case |
|-------|---------|----------|
| `ARRIVED` | Successfully reached goal | Completion notifications |
| `CANCELED` | Path manually canceled | Cleanup operations |
| `CALCULATION_COMPLETE` | Path calculation done | Progress updates |
| `CALCULATION_FAILED` | No path found | Error handling |
| `NEXT_SEGMENT` | Moving to next path segment | Progress tracking |
| `PATH_STARTED` | Beginning path execution | Initialization |

### 5.2 Failure Handling

```java
public class FailureHandling {
    
    private final IPathingBehavior pathing;
    private final Settings settings;
    
    /**
     * Comprehensive failure handler
     */
    public void registerFailureHandler() {
        baritone.getGameEventHandler().registerEventListener(
            new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    handlePathEvent(event);
                }
            }
        );
    }
    
    private void handlePathEvent(PathEvent event) {
        switch (event) {
            case CALCULATION_FAILED:
                handleCalculationFailure();
                break;
            case STUCK:
                handleStuckCondition();
                break;
            case CANCELED:
                handleCancellation();
                break;
        }
    }
    
    private void handleCalculationFailure() {
        // Path couldn't be calculated - goal unreachable
        System.err.println("Path calculation failed - goal may be unreachable");
        
        // Try alternative approaches
        Goal originalGoal = pathing.getGoal();
        if (originalGoal instanceof GoalBlock) {
            // Try nearby alternative
            GoalBlock blockGoal = (GoalBlock) originalGoal;
            GoalXZ alternative = new GoalXZ(blockGoal.x, blockGoal.z);
            
            pathing.setGoal(alternative);
            pathing.pathToGoal();
        }
    }
    
    private void handleStuckCondition() {
        // Bot is stuck - try to recover
        System.err.println("Bot appears stuck - attempting recovery");
        
        // Cancel current path
        pathing.cancelEverything();
        
        // Attempt small random movement
        attemptRecoveryMovement();
    }
    
    private void attemptRecoveryMovement() {
        // Try moving in a random direction briefly
        Random random = new Random();
        int dx = random.nextInt(5) - 2;
        int dz = random.nextInt(5) - 2;
        
        BlockPos current = baritone.getPlayerContext().playerFeet();
        GoalBlock recoveryGoal = new GoalBlock(
            current.getX() + dx,
            current.getY(),
            current.getZ() + dz
        );
        
        pathing.setGoal(recoveryGoal);
        pathing.pathToGoal();
    }
}
```

### 5.3 Progress Monitoring

```java
public class ProgressMonitor {
    
    private final IPathingBehavior pathing;
    private IPath currentPath;
    private int totalNodes;
    private int currentNode;
    
    /**
     * Register progress tracking
     */
    public void registerProgressTracker() {
        baritone.getGameEventHandler().registerEventListener(
            new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    updateProgress(event);
                }
                
                @Override
                public void onTick(TickEvent event) {
                    if (event.getState() == EventState.POST) {
                        reportProgress();
                    }
                }
            }
        );
    }
    
    private void updateProgress(PathEvent event) {
        switch (event) {
            case PATH_STARTED:
                currentPath = pathing.getCurrent();
                if (currentPath != null) {
                    totalNodes = currentPath.positions().size();
                    currentNode = 0;
                }
                break;
            case NEXT_SEGMENT:
                currentNode++;
                break;
        }
    }
    
    private void reportProgress() {
        if (currentPath != null && totalNodes > 0) {
            double progress = (double) currentNode / totalNodes * 100;
            System.out.printf("Path progress: %.1f%% (%d/%d nodes)%n",
                progress, currentNode, totalNodes);
        }
    }
    
    /**
     * Get ETA estimation
     */
    public Optional<Duration> estimateTimeRemaining() {
        if (currentPath == null || currentNode >= totalNodes) {
            return Optional.empty();
        }
        
        // Estimate based on average movement speed
        double avgSpeed = 4.0; // blocks per second (walking)
        int remainingNodes = totalNodes - currentNode;
        double estimatedSeconds = remainingNodes / avgSpeed;
        
        return Optional.of(Duration.ofSeconds((long) estimatedSeconds));
    }
}
```

---

## 6. Settings and Configuration

### 6.1 Key Settings Reference

#### Pathfinding Settings

```java
public class PathfindingConfiguration {
    
    private final Settings settings;
    
    public void configureForExploration() {
        // Long-range pathfinding
        settings.pathingMaxChunkBorderFetch.value = 100;
        settings.pathingTimeoutMS.value = 10000L;
        
        // Allow risky movement
        settings.allowParkour.value = true;
        settings.allowParkourPlace.value = true;
        settings.allowSprint.value = true;
        
        // Be willing to break/place
        settings.allowBreak.value = true;
        settings.allowPlace.value = true;
    }
    
    public void configureForSafety() {
        // Conservative pathfinding
        settings.pathingMaxChunkBorderFetch.value = 25;
        settings.pathingTimeoutMS.value = 2000L;
        
        // Avoid risky movement
        settings.allowParkour.value = false;
        settings.allowParkourPlace.value = false;
        settings.allowSprint.value = false;
        
        // Don't modify world
        settings.allowBreak.value = false;
        settings.allowPlace.value = false;
    }
    
    public void configureForMining() {
        // Optimize for mining operations
        settings.pathingMaxChunkBorderFetch.value = 50;
        
        // Allow descent into caves
        settings.allowDownward.value = true;
        settings.allowDownwardFall.value = 3; // Small falls OK
        
        // Tool switching
        settings.mineToolRequirement.value = true;
        settings.mineToolSilkTouch.value = false;
        
        // Inventory management
        settings.mineScanDroppedItems.value = true;
        settings.mineDropLoiterDurationMSThanksLouca.value = 500;
    }
}
```

#### Safety Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `allowBreak` | Boolean | true | Allow breaking blocks |
| `allowPlace` | Boolean | true | Allow placing blocks |
| `allowSprint` | Boolean | true | Allow sprinting |
| `allowParkour` | Boolean | true | Allow parkour jumps |
| `allowParkourPlace` | Boolean | true | Allow placing for parkour |
| `allowDownward` | Boolean | true | Allow descending |
| `allowDiagonalDescend` | Boolean | true | Allow diagonal down |
| `allowDiagonalAscend` | Boolean | true | Allow diagonal up |
| `avoidance` | Boolean | true | Avoid mobs/danger |
| `mobAvoidanceRadius` | Double | 8.0 | Mob avoidance distance |
| `mobAvoidanceCoefficient` | Double | 1.5 | Cost multiplier for mobs |

#### Performance Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `pathingMaxChunkBorderFetch` | Integer | 50 | Max chunks to load |
| `primaryTimeoutMS` | Long | 5000 | Primary path timeout |
| `failureTimeoutMS` | Long | 2000 | Failure retry timeout |
| `planAhead` | Boolean | true | Plan path ahead |
| `pathCutoffFactor` | Double | 3.0 | Path cutoff multiplier |
| `pathCutoffMinimum` | Integer | 50 | Minimum path cutoff |
| `maxFallHeightNoWater` | Integer | 3 | Max fall without water |
| `maxFallHeightBucket` | Integer | 256 | Max fall with bucket |

### 6.2 Programmatic vs Config Files

#### Programmatic Configuration (Recommended)

```java
public class DynamicConfiguration {
    
    /**
     * Apply configuration based on current context
     */
    public void applyContextualConfig(GameContext context) {
        Settings settings = BaritoneAPI.getSettings();
        
        switch (context.getGameMode()) {
            case SURVIVAL:
                configureSurvival(settings);
                break;
            case CREATIVE:
                configureCreative(settings);
                break;
            case HARDCORE:
                configureHardcore(settings);
                break;
        }
        
        // Adjust for environment
        if (context.isInNether()) {
            configureForNether(settings);
        }
        
        if (context.isLowOnHealth()) {
            configureDefensive(settings);
        }
    }
    
    private void configureSurvival(Settings s) {
        s.allowBreak.value = true;
        s.allowPlace.value = true;
        s.allowSprint.value = true;
        s.allowParkour.value = true;
        s.avoidance.value = true;
        s.mobAvoidanceRadius.value = 10.0;
    }
    
    private void configureHardcore(Settings s) {
        // Ultra-conservative for hardcore
        s.allowParkour.value = false;
        s.allowParkourPlace.value = false;
        s.maxFallHeightNoWater.value = 2;
        s.avoidance.value = true;
        s.mobAvoidanceRadius.value = 16.0;
        s.mobAvoidanceCoefficient.value = 2.0;
    }
    
    private void configureForNether(Settings s) {
        // Extra lava avoidance
        s.avoidance.value = true;
        s.lavaSafetyDistance.value = 3;
        
        // Ghast avoidance
        s.mobAvoidanceRadius.value = 20.0;
    }
    
    private void configureDefensive(Settings s) {
        // When low on health
        s.avoidance.value = true;
        s.mobAvoidanceRadius.value = 32.0;
        s.allowSprint.value = false; // Save hunger
    }
}
```

#### Configuration File (settings.txt)

```
# Baritone Settings File
# Location: .minecraft/baritone/settings.txt

# Pathfinding
allowBreak true
allowPlace true
allowSprint true
allowParkour true
pathingMaxChunkBorderFetch 50

# Safety
avoidance true
mobAvoidanceRadius 8.0
maxFallHeightNoWater 3

# Mining
mineScanDroppedItems true
mineDropLoiterDurationMSThanksLouca 250

# Performance
primaryTimeoutMS 5000
failureTimeoutMS 2000
```

---

## 7. Error Handling and Edge Cases

### 7.1 Blocked Path Handling

```java
public class BlockedPathHandler {
    
    private final IPathingBehavior pathing;
    private final Settings settings;
    
    /**
     * Handle path blocked by unbreakable obstacle
     */
    public void handleBlockedPath(BlockPos obstaclePos) {
        Block block = world.getBlockState(obstaclePos).getBlock();
        
        if (isUnbreakable(block)) {
            // Try alternative routing
            attemptAlternativeRoute(obstaclePos);
        } else if (isBreakableButDangerous(block)) {
            // Evaluate risk vs reward
            if (shouldAttemptBreak(obstaclePos)) {
                settings.allowBreak.value = true;
            } else {
                attemptAlternativeRoute(obstaclePos);
            }
        }
    }
    
    private void attemptAlternativeRoute(BlockPos obstacle) {
        Goal originalGoal = pathing.getGoal();
        
        // Try pathing around obstacle
        List<Goal> alternatives = generateAlternativeGoals(obstacle, originalGoal);
        
        for (Goal alt : alternatives) {
            pathing.setGoal(alt);
            if (pathing.pathToGoal() == PathingResult.SUCCESS) {
                return; // Found viable alternative
            }
        }
        
        // No alternative found
        throw new PathNotFoundException("No alternative path available");
    }
    
    private List<Goal> generateAlternativeGoals(BlockPos obstacle, Goal original) {
        List<Goal> alternatives = new ArrayList<>();
        
        // Generate goals around obstacle
        for (int dx = -5; dx <= 5; dx += 2) {
            for (int dz = -5; dz <= 5; dz += 2) {
                if (dx == 0 && dz == 0) continue;
                
                BlockPos alt = obstacle.offset(dx, 0, dz);
                alternatives.add(new GoalBlock(alt.getX(), alt.getY(), alt.getZ()));
            }
        }
        
        return alternatives;
    }
}
```

### 7.2 Lava Avoidance

```java
public class LavaSafetyManager {
    
    private final Settings settings;
    private final IBaritone baritone;
    
    public void configureLavaSafety() {
        // Maximum lava avoidance
        settings.avoidance.value = true;
        settings.lavaSafetyDistance.value = 3; // Stay 3 blocks from lava
        
        // Disable risky movement near lava
        settings.allowParkour.value = false;
        settings.allowParkourPlace.value = false;
        settings.maxFallHeightNoWater.value = 2;
    }
    
    /**
     * Emergency handler for unexpected lava
     */
    public void handleLavaEncounter(BlockPos lavaPos) {
        // Immediate stop
        baritone.getPathingBehavior().cancelEverything();
        
        // Find safe direction
        Direction safeDir = findSafeDirection(lavaPos);
        
        if (safeDir != null) {
            BlockPos current = baritone.getPlayerContext().playerFeet();
            BlockPos safePos = current.relative(safeDir, 3);
            
            // Path to safety
            GoalBlock safetyGoal = new GoalBlock(safePos.getX(), safePos.getY(), safePos.getZ());
            baritone.getPathingBehavior().setGoal(safetyGoal);
            baritone.getPathingBehavior().pathToGoal();
        }
    }
    
    private Direction findSafeDirection(BlockPos lavaPos) {
        BlockPos player = baritone.getPlayerContext().playerFeet();
        
        // Check all directions for safety
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() == Direction.Axis.Y) continue;
            
            BlockPos check = player.relative(dir, 3);
            if (isSafeArea(check)) {
                return dir;
            }
        }
        return null;
    }
    
    private boolean isSafeArea(BlockPos center) {
        // Check 3x3 area for lava
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    Block block = world.getBlockState(check).getBlock();
                    if (block == Blocks.LAVA || block == Blocks.FIRE) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
```

### 7.3 Death and Respawn Handling

```java
public class DeathRespawnHandler {
    
    private final IBaritone baritone;
    private BlockPos deathLocation;
    private List<ItemStack> inventorySnapshot;
    
    /**
     * Register death detection
     */
    public void registerDeathHandler() {
        baritone.getGameEventHandler().registerEventListener(
            new AbstractGameEventListener() {
                @Override
                public void onPlayerDeath(PlayerDeathEvent event) {
                    handleDeath(event);
                }
                
                @Override
                public void onPlayerRespawn(PlayerRespawnEvent event) {
                    handleRespawn(event);
                }
            }
        );
    }
    
    private void handleDeath(PlayerDeathEvent event) {
        // Record death location
        deathLocation = baritone.getPlayerContext().playerFeet();
        
        // Snapshot inventory for recovery assessment
        inventorySnapshot = new ArrayList<>(
            baritone.getPlayerContext().player().getInventory().items
        );
        
        // Cancel all behaviors
        baritone.getPathingBehavior().cancelEverything();
        baritone.getMineBehavior().cancel();
        baritone.getFollowBehavior().cancel();
        
        System.out.println("Death recorded at: " + deathLocation);
    }
    
    private void handleRespawn(PlayerRespawnEvent event) {
        // Assess situation
        boolean hasValuableItems = assessInventoryLoss();
        
        if (hasValuableItems && deathLocation != null) {
            // Attempt recovery
            scheduleRecoveryRun();
        } else {
            // Resume normal operation
            resumePreviousTask();
        }
    }
    
    private void scheduleRecoveryRun() {
        // Wait for respawn protection to expire
        try {
            Thread.sleep(3000); // 3 second protection
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Path back to death location
        GoalBlock recoveryGoal = new GoalBlock(
            deathLocation.getX(),
            deathLocation.getY(),
            deathLocation.getZ()
        );
        
        // Configure for cautious approach
        Settings settings = baritone.getSettings();
        boolean oldParkour = settings.allowParkour.value;
        boolean oldSprint = settings.allowSprint.value;
        
        settings.allowParkour.value = false;
        settings.allowSprint.value = false;
        
        // Set up arrival callback to restore settings
        registerOneShotArrivalListener(() -> {
            settings.allowParkour.value = oldParkour;
            settings.allowSprint.value = oldSprint;
            scanForDroppedItems();
        });
        
        baritone.getPathingBehavior().setGoal(recoveryGoal);
        baritone.getPathingBehavior().pathToGoal();
    }
    
    private void scanForDroppedItems() {
        // Look for dropped items near death location
        baritone.getSettings().mineScanDroppedItems.value = true;
        // Wait and scan...
    }
}
```

### 7.4 World Loading/Unloading

```java
public class ChunkManagement {
    
    private final IPathingBehavior pathing;
    private final Settings settings;
    
    /**
     * Handle chunk loading for long-distance pathing
     */
    public void configureChunkLoading() {
        // Limit chunk loading to prevent lag
        settings.pathingMaxChunkBorderFetch.value = 50;
        
        // Enable chunk caching
        settings.chunkCaching.value = true;
        settings.chunkCacheSize.value = 1000;
    }
    
    /**
     * Check if goal is in loaded chunks
     */
    public boolean isGoalReachable(Goal goal) {
        if (goal instanceof GoalBlock) {
            GoalBlock blockGoal = (GoalBlock) goal;
            BlockPos pos = new BlockPos(blockGoal.x, blockGoal.y, blockGoal.z);
            
            return isChunkLoaded(pos);
        }
        return true; // Assume XZ goals are reachable
    }
    
    private boolean isChunkLoaded(BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        return baritone.getPlayerContext().world()
            .hasChunk(chunkPos.x, chunkPos.z);
    }
    
    /**
     * Wait for chunk loading before pathing
     */
    public void pathWithChunkLoading(Goal goal) {
        if (!isGoalReachable(goal)) {
            // Path towards goal, loading chunks incrementally
            pathIncrementally(goal);
        } else {
            pathing.setGoal(goal);
            pathing.pathToGoal();
        }
    }
    
    private void pathIncrementally(Goal finalGoal) {
        // Break journey into segments
        List<Goal> segments = calculateSegments(finalGoal);
        
        for (Goal segment : segments) {
            pathing.setGoal(segment);
            PathingResult result = pathing.pathToGoal();
            
            if (result != PathingResult.SUCCESS) {
                // Wait for chunks to load
                waitForChunks(segment);
            }
        }
    }
    
    private void waitForChunks(Goal nearGoal) {
        int attempts = 0;
        while (!isGoalReachable(nearGoal) && attempts < 50) {
            try {
                Thread.sleep(100); // Wait 100ms
                attempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
```

---

## 8. Best Practices

### 8.1 Performance Considerations

```java
public class PerformanceOptimization {
    
    /**
     * Optimize for server play (reduced lag)
     */
    public void optimizeForServer(Settings settings) {
        // Reduce path calculation frequency
        settings.pathingTimeoutMS.value = 3000L;
        settings.primaryTimeoutMS.value = 3000L;
        
        // Limit chunk loading
        settings.pathingMaxChunkBorderFetch.value = 25;
        
        // Reduce block interactions
        settings.blockReachDistance.value = 4.0f;
        settings.rightClickSpeed.value = 4;
        
        // Conservative movement
        settings.allowSprint.value = false;
        settings.allowParkour.value = false;
    }
    
    /**
     * Optimize for single-player (full performance)
     */
    public void optimizeForSinglePlayer(Settings settings) {
        // Aggressive pathfinding
        settings.pathingTimeoutMS.value = 10000L;
        settings.pathingMaxChunkBorderFetch.value = 100;
        
        // Enable all features
        settings.allowSprint.value = true;
        settings.allowParkour.value = true;
        settings.allowParkourPlace.value = true;
        
        // Fast interactions
        settings.rightClickSpeed.value = 10;
        settings.leftClickSpeed.value = 10;
    }
    
    /**
     * Memory management for long-running bots
     */
    public void configureMemoryManagement(Settings settings) {
        // Limit cache sizes
        settings.chunkCacheSize.value = 500;
        settings.mineMaxCachedOreLocations.value = 5000;
        
        // Regular cleanup
        settings.pathCacheSize.value = 100;
        settings.movementTimeoutTicks.value = 100;
    }
}
```

### 8.2 Thread Safety

```java
public class ThreadSafeOperations {
    
    private final IBaritone baritone;
    private final ExecutorService executor;
    
    public ThreadSafeOperations(IBaritone baritone) {
        this.baritone = baritone;
        this.executor = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Thread-safe command execution
     */
    public void executeAsync(Runnable command) {
        // Baritone operations should run on main thread
        // Use Minecraft's task queue
        Minecraft.getInstance().execute(() -> {
            try {
                command.run();
            } catch (Exception e) {
                System.err.println("Command failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Thread-safe goal setting
     */
    public void setGoalThreadSafe(Goal goal) {
        executeAsync(() -> {
            baritone.getPathingBehavior().setGoal(goal);
            baritone.getPathingBehavior().pathToGoal();
        });
    }
    
    /**
     * Thread-safe status checking
     */
    public CompletableFuture<Boolean> isPathingAsync() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        executeAsync(() -> {
            boolean isPathing = baritone.getPathingBehavior().isPathing();
            future.complete(isPathing);
        });
        
        return future;
    }
}
```

### 8.3 Memory Management

```java
public class MemoryManagement {
    
    /**
     * Periodic cleanup for long-running bots
     */
    public void performCleanup(IBaritone baritone) {
        // Clear path cache
        baritone.getPathingBehavior().clearPathCache();
        
        // Clear ore location cache
        baritone.getMineBehavior().clearOreCache();
        
        // Clear chunk cache
        Settings settings = baritone.getSettings();
        settings.chunkCacheSize.value = Math.max(100, settings.chunkCacheSize.value / 2);
    }
    
    /**
     * Schedule periodic cleanup
     */
    public void schedulePeriodicCleanup(IBaritone baritone, long intervalMinutes) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        scheduler.scheduleAtFixedRate(
            () -> performCleanup(baritone),
            intervalMinutes,
            intervalMinutes,
            TimeUnit.MINUTES
        );
    }
}
```

---

## 9. Troubleshooting Guide

### 9.1 Common Issues and Solutions

#### Issue: Path calculation fails immediately

```java
// Diagnosis
public void diagnosePathFailure(Goal goal) {
    System.out.println("Path failure diagnosis:");
    System.out.println("Goal: " + goal);
    System.out.println("Current position: " + baritone.getPlayerContext().playerFeet());
    System.out.println("Is chunk loaded: " + isChunkLoaded(goal));
    System.out.println("Allow break: " + settings.allowBreak.value);
    System.out.println("Allow place: " + settings.allowPlace.value);
    
    // Check for common issues
    if (!isChunkLoaded(goal)) {
        System.out.println("ISSUE: Goal chunk not loaded");
    }
    
    if (goal instanceof GoalBlock) {
        GoalBlock gb = (GoalBlock) goal;
        if (gb.y < -64 || gb.y > 320) {
            System.out.println("ISSUE: Y coordinate out of bounds");
        }
    }
}
```

**Solutions:**
1. **Chunk not loaded**: Reduce `pathingMaxChunkBorderFetch` or path incrementally
2. **Y coordinate out of bounds**: Validate coordinates before pathing
3. **No valid path**: Enable `allowBreak` and `allowPlace` if needed
4. **Timeout too short**: Increase `primaryTimeoutMS`

#### Issue: Bot gets stuck repeatedly

```java
// Stuck detection and recovery
public void implementStuckRecovery() {
    BlockPos lastPos = null;
    int stuckTicks = 0;
    final int STUCK_THRESHOLD = 40; // 2 seconds at 20 TPS
    
    baritone.getGameEventHandler().registerEventListener(
        new AbstractGameEventListener() {
            @Override
            public void onTick(TickEvent event) {
                if (event.getState() != EventState.POST) return;
                
                BlockPos current = baritone.getPlayerContext().playerFeet();
                
                if (lastPos != null && current.equals(lastPos)) {
                    stuckTicks++;
                    
                    if (stuckTicks >= STUCK_THRESHOLD) {
                        System.out.println("STUCK DETECTED - Executing recovery");
                        executeStuckRecovery();
                        stuckTicks = 0;
                    }
                } else {
                    stuckTicks = 0;
                }
                
                lastPos = current;
            }
        }
    );
}
```

**Solutions:**
1. **Disable parkour**: Set `allowParkour` to false
2. **Reduce path complexity**: Lower `pathingMaxChunkBorderFetch`
3. **Enable stuck recovery**: Implement stuck detection
4. **Check for invisible blocks**: Some modded blocks may block pathing

#### Issue: Mining stops unexpectedly

```java
// Mining diagnostics
public void diagnoseMiningIssues() {
    IMineBehavior mining = baritone.getMineBehavior();
    
    System.out.println("Mining diagnostics:");
    System.out.println("Is mining: " + mining.isMining());
    System.out.println("Current target: " + mining.getCurrentTarget());
    System.out.println("Ore locations count: " + mining.getOreLocationsCount());
    System.out.println("Inventory full: " + isInventoryFull());
    System.out.println("Tool available: " + hasAppropriateTool());
}
```

**Solutions:**
1. **Inventory full**: Implement inventory management
2. **No tool**: Check `mineToolRequirement` setting
3. **No ore visible**: Increase `mineScanRadius`
4. **Tool broken**: Enable automatic tool switching

### 9.2 Debug Logging

```java
public class DebugLogging {
    
    private final Logger logger = LoggerFactory.getLogger(DebugLogging.class);
    
    /**
     * Enable comprehensive debug logging
     */
    public void enableDebugLogging(IBaritone baritone) {
        Settings settings = baritone.getSettings();
        
        // Enable internal debug
        settings.debug.value = true;
        settings.chatDebug.value = true;
        settings.logAsToast.value = false; // Log to console, not toasts
        
        // Register custom logger
        baritone.getGameEventHandler().registerEventListener(
            new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    logger.debug("PathEvent: {}", event);
                }
                
                @Override
                public void onTick(TickEvent event) {
                    if (event.getState() == EventState.PRE) {
                        logPathingStatus();
                    }
                }
            }
        );
    }
    
    private void logPathingStatus() {
        IPathingBehavior pathing = baritone.getPathingBehavior();
        
        if (pathing.isPathing()) {
            logger.debug("Pathing: {} nodes remaining, goal: {}",
                pathing.getCurrent() != null ? 
                    pathing.getCurrent().positions().size() : 0,
                pathing.getGoal()
            );
        }
    }
    
    /**
     * Log settings for debugging
     */
    public void logCurrentSettings(Settings settings) {
        logger.info("=== Baritone Settings ===");
        logger.info("allowBreak: {}", settings.allowBreak.value);
        logger.info("allowPlace: {}", settings.allowPlace.value);
        logger.info("allowParkour: {}", settings.allowParkour.value);
        logger.info("pathingMaxChunkBorderFetch: {}", settings.pathingMaxChunkBorderFetch.value);
        logger.info("primaryTimeoutMS: {}", settings.primaryTimeoutMS.value);
        logger.info("avoidance: {}", settings.avoidance.value);
        logger.info("========================");
    }
}
```

### 9.3 Diagnostic Checklist

```java
public class DiagnosticChecklist {
    
    public DiagnosticReport runDiagnostics(IBaritone baritone) {
        DiagnosticReport report = new DiagnosticReport();
        
        // 1. Check initialization
        report.addCheck("Baritone initialized", baritone != null);
        
        // 2. Check settings
        Settings settings = baritone.getSettings();
        report.addCheck("Settings accessible", settings != null);
        report.addCheck("Allow break enabled", settings.allowBreak.value);
        report.addCheck("Allow place enabled", settings.allowPlace.value);
        
        // 3. Check behaviors
        report.addCheck("Pathing behavior", baritone.getPathingBehavior() != null);
        report.addCheck("Mine behavior", baritone.getMineBehavior() != null);
        report.addCheck("Follow behavior", baritone.getFollowBehavior() != null);
        
        // 4. Check player context
        report.addCheck("Player context", baritone.getPlayerContext() != null);
        report.addCheck("Player entity", baritone.getPlayerContext().player() != null);
        report.addCheck("World loaded", baritone.getPlayerContext().world() != null);
        
        // 5. Check current state
        IPathingBehavior pathing = baritone.getPathingBehavior();
        report.addCheck("Not stuck", !isLikelyStuck(baritone));
        report.addCheck("Valid position", isValidPosition(
            baritone.getPlayerContext().playerFeet()
        ));
        
        // 6. Performance checks
        report.addCheck("Reasonable chunk fetch", 
            settings.pathingMaxChunkBorderFetch.value <= 100);
        report.addCheck("Reasonable timeout",
            settings.primaryTimeoutMS.value >= 1000);
        
        return report;
    }
    
    private boolean isLikelyStuck(IBaritone baritone) {
        // Implementation: check if position hasn't changed in X ticks
        return false; // Placeholder
    }
    
    private boolean isValidPosition(BlockPos pos) {
        return pos.getY() >= -64 && pos.getY() <= 320;
    }
}
```

---

## Appendix A: Quick Reference Card

### Essential Imports

```java
// Core API
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.Settings;

// Behaviors
import baritone.api.behavior.IPathingBehavior;
import baritone.api.behavior.IMineBehavior;
import baritone.api.behavior.IFollowBehavior;
import baritone.api.behavior.IBuilderBehavior;

// Goals
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.goals.GoalComposite;

// Events
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.event.events.PathEvent;

// Utils
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
```

### Common Patterns

```java
// Initialize
IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

// Path to coordinates
baritone.getPathingBehavior().setGoal(new GoalBlock(x, y, z));
baritone.getPathingBehavior().pathToGoal();

// Mine ore
baritone.getMineBehavior().mine(quantity, new BlockOptionalMeta(Blocks.DIAMOND_ORE));

// Follow player
baritone.getFollowBehavior().follow("PlayerName");

// Stop everything
baritone.getPathingBehavior().cancelEverything();

// Listen for completion
baritone.getGameEventHandler().registerEventListener(
    new AbstractGameEventListener() {
        @Override
        public void onPathEvent(PathEvent event) {
            if (event == PathEvent.ARRIVED) {
                // Handle arrival
            }
        }
    }
);
```

### Critical Settings

```java
Settings s = BaritoneAPI.getSettings();
s.allowBreak.value = true;      // Can break blocks
s.allowPlace.value = true;      // Can place blocks
s.allowParkour.value = true;    // Can jump gaps
s.allowSprint.value = true;     // Can sprint
s.avoidance.value = true;         // Avoid mobs/lava
s.pathingMaxChunkBorderFetch.value = 50;  // Chunk loading limit
s.primaryTimeoutMS.value = 5000L;        // Path calc timeout
```

---

## Appendix B: Maven/Gradle Coordinates Reference

### Latest Stable Versions

| Minecraft | Baritone Version | JitPack Coordinate |
|-----------|------------------|-------------------|
| 1.20.4 | v1.10.1 | `com.github.cabaletta:baritone:v1.10.1` |
| 1.20.1 | v1.10.0 | `com.github.cabaletta:baritone:v1.10.0` |
| 1.19.4 | v1.9.4 | `com.github.cabaletta:baritone:v1.9.4` |
| 1.18.2 | v1.8.5 | `com.github.cabaletta:baritone:v1.8.5` |
| 1.16.5 | v1.6.5 | `com.github.cabaletta:baritone:v1.6.5` |
| 1.12.2 | v1.2.15 | `com.github.cabaletta:baritone:v1.2.15` |

### Classifier Options

- `api` - Mapped (obfuscated) for specific MC version
- `api-unoptimized` - Unmapped (development)
- `standalone` - Full client (not for API use)
- `forge` / `fabric` - Platform-specific builds

---

## Appendix C: Resources

- **GitHub**: https://github.com/cabaletta/baritone
- **Documentation**: https://baritone.leijurv.com/
- **Discord**: https://discord.gg/baritone
- **JitPack**: https://jitpack.io/#cabaletta/baritone

---

*Document Version: 1.0*  
*Generated for Baritone API v1.10.x*  
*Minecraft Java Edition 1.12.2 - 1.20.4*
