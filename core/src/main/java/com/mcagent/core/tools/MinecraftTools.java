package com.mcagent.core.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcagent.core.memory.LocationMemoryEntry;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.LocationType;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
import com.mcagent.core.memory.episodic.EpisodicMemoryService;
import com.mcagent.core.memory.episodic.SessionContext;
import com.mcagent.core.model.BotAction;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.routine.RoutineDefinition;
import com.mcagent.core.routine.RoutineNotFoundException;
import com.mcagent.core.routine.RoutineService;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.service.PlanDraft;
import com.mcagent.core.service.PlanFromDraftConverter;
import com.mcagent.core.service.PlanValidationException;
import com.mcagent.core.service.Planner;
import com.mcagent.core.service.SafetyValidator;
import com.mcagent.core.task.PendingApprovalStore;
import com.mcagent.core.task.Plan;
import com.mcagent.core.task.PlanApprovalGate;
import com.mcagent.core.task.PlanSerializer;
import com.mcagent.core.task.RetryPolicy;
import com.mcagent.core.task.Step;
import com.mcagent.core.task.TaskExecutor;
import com.mcagent.core.task.TaskSnapshot;
import com.mcagent.core.task.TerminationPolicy;
import com.mcagent.core.task.postcondition.InventoryDecreased;
import com.mcagent.core.task.postcondition.InventoryIncreased;
import com.mcagent.core.task.postcondition.NoOp;
import com.mcagent.core.task.postcondition.PostCondition;
import com.mcagent.core.task.postcondition.PostConditionChecker;
import com.mcagent.core.task.postcondition.PositionWithin;
import com.mcagent.core.task.postcondition.WorldStateSnapshot;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Tools exposed to the LLM for Minecraft interactions.
 * These methods are discovered by LangChain4j and presented to the model.
 *
 * <h3>Post-condition self-verification (Sprint 13 A5)</h3>
 * <p>Each meaningful tool records a post-condition before execution and verifies it
 * after. The result is emitted via {@link LangChain4jService#addFrameworkContext}
 * so both the LLM and {@link com.mcagent.core.task.JobSupervisor} can observe whether
 * the expected world-state change occurred. {@link NoOp} post-conditions are silently
 * skipped to avoid framework-message noise.</p>
 *
 * <p>The static registry {@link #POST_CONDITIONS} maps each tool name to a factory
 * that builds a {@link PostCondition} from the raw tool arguments. External callers
 * (e.g. {@code JobSupervisor}) may query it via {@link #postConditionFor(String, Object...)}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinecraftTools {

    // -------------------------------------------------------------------------
    // Post-condition registry (Sprint 13 A5)
    // -------------------------------------------------------------------------

    /**
     * Pairs a {@link PostCondition} with a flag indicating whether the tool is synchronous.
     *
     * <p>Synchronous tools complete their world-state change before returning, so the
     * post-condition can be evaluated immediately. Async tools (Baritone-backed) dispatch
     * work in the background — capturing an "after" snapshot immediately after the tool
     * returns would always observe an unchanged world and produce false negatives.</p>
     */
    private record ToolPC(PostCondition pc, boolean sync) {}

    /**
     * Maps tool name → a factory that produces a {@link ToolPC} from the raw tool
     * arguments in declaration order.
     *
     * <p>Async tools are registered with {@code sync=false} so the post-condition
     * framework message is emitted as "verification pending" rather than an immediate
     * (and incorrect) delta check.</p>
     *
     * <p>Argument ordering mirrors the corresponding {@code @Tool} method signature.
     * Entries not listed here default to {@link NoOp} via {@link #postConditionFor}.</p>
     */
    private static final Map<String, Function<Object[], ToolPC>> POST_CONDITIONS =
            Map.ofEntries(
                // Synchronous tools — world state changes before method returns.
                Map.entry("placeBlockAt",
                        args -> new ToolPC(new InventoryDecreased((String) args[3], 1), true)),
                Map.entry("eatFood",
                        args -> new ToolPC(new NoOp(), true)),
                Map.entry("placeTorchAtFeet",
                        args -> new ToolPC(new InventoryDecreased("minecraft:torch", 1), true)),
                // Async tools — Baritone runs in the background; snapshot after return is stale.
                Map.entry("mineResource",
                        args -> new ToolPC(new InventoryIncreased((String) args[0], (Integer) args[1]), false)),
                Map.entry("navigateTo",
                        args -> new ToolPC(new PositionWithin((Integer) args[0], (Integer) args[1], (Integer) args[2], 3), false)),
                Map.entry("buildArea",
                        args -> {
                            int volume = Math.abs(((Integer) args[3]) - ((Integer) args[0]) + 1)
                                       * Math.abs(((Integer) args[4]) - ((Integer) args[1]) + 1)
                                       * Math.abs(((Integer) args[5]) - ((Integer) args[2]) + 1);
                            return new ToolPC(new InventoryDecreased((String) args[6], volume), false);
                        })
            );

    /**
     * Tool names that are async (Baritone-backed). Used by {@link #withToolScope} to
     * determine whether to capture an "after" snapshot or emit a "pending" notice.
     */
    private static final java.util.Set<String> ASYNC_TOOLS = Set.of(
            "mineResource", "navigateTo", "buildArea"
    );

    /**
     * Look up the expected post-condition for a tool by name.
     * Returns {@link Optional#empty()} when no entry is registered (callers may
     * treat absence as a {@link NoOp}).
     *
     * @param toolName the name matching a {@code @Tool} method name
     * @param args     tool arguments in declaration order
     */
    public static Optional<PostCondition> postConditionFor(String toolName, Object... args) {
        Function<Object[], ToolPC> fn = POST_CONDITIONS.get(toolName);
        return Optional.ofNullable(fn).map(f -> f.apply(args).pc());
    }

    private static final ObjectMapper JACKSON = new ObjectMapper();

    private final BotOperations bot;
    private final LocationMemoryService locationMemory;
    private final VectorMemoryService vectorMemory;
    private final PlayerNoteService playerNoteService;
    private final ChatService chatService;
    private final SafetyValidator safetyValidator;
    private final JobTracker jobTracker;
    private final Counters counters;
    private final RunLog runLog;
    private final TaskExecutor taskExecutor;
    private final Planner planner;
    private final PlanApprovalGate approvalGate;
    private final PendingApprovalStore pendingApprovalStore;
    private final RoutineService routineService;
    private final EpisodicMemoryService episodicMemoryService;
    private final SessionContext sessionContext;

    /**
     * Injected lazily to break the {@code MinecraftTools → LangChain4jService → Assistant → MinecraftTools}
     * circular dependency. Spring resolves lazy references via a proxy, so this field is safe to use
     * after construction.
     */
    @Setter(onMethod_ = {@Autowired, @Lazy})
    private LangChain4jService langChain4jService;

    @Tool("Follow a specific player by name. The bot will path to and continue following them.")
    public String followPlayer(
            @P("The player's name to follow") String playerName) {
        log.info("Tool: followPlayer({})", playerName);
        return withToolScope("followPlayer", () -> {
            jobTracker.setCurrentJob("Following " + playerName, "unknown");
            var result = bot.followPlayer(playerName);
            if (result.isSuccess()) {
                chatService.send("Following player " + playerName);
                return "Following player " + playerName;
            }
            return "Cannot follow: " + result.getMessage();
        });
    }

    @Tool("Navigate to specific X Y Z coordinates")
    public String navigateTo(
            @P("X coordinate") int x,
            @P("Y coordinate") int y,
            @P("Z coordinate") int z) {
        log.info("Tool: navigateTo({}, {}, {})", x, y, z);
        return withToolScope("navigateTo", new PositionWithin(x, y, z, 3), () -> {
            jobTracker.setCurrentJob("Navigating to (" + x + ", " + y + ", " + z + ")", "unknown");
            var result = bot.navigateTo(x, y, z);
            if (result.isSuccess()) {
                chatService.send("Navigating to (" + x + ", " + y + ", " + z + ")");
                return "Navigating to (" + x + ", " + y + ", " + z + ")";
            }
            return "Cannot navigate: " + result.getMessage();
        });
    }

    @Tool("Navigate to a previously remembered location by name")
    public String navigateToLocation(
            @P("Name of the remembered location") String locationName) {
        log.info("Tool: navigateToLocation({})", locationName);
        return withToolScope("navigateToLocation", () -> {
            jobTracker.setCurrentJob("Navigating to '" + locationName + "'", "unknown");
            var found = locationMemory.findByName(locationName);
            if (found.isEmpty()) {
                return "I don't remember a location named '" + locationName + "'.";
            }
            var loc = found.get();
            var result = bot.navigateTo(loc.getX(), loc.getY(), loc.getZ());
            return result.isSuccess()
                    ? "Navigating to '" + locationName + "' at (" + loc.getX() + ", " + loc.getY() + ", " + loc.getZ() + ")"
                    : "Cannot navigate to '" + locationName + "': " + result.getMessage();
        });
    }

    @Tool("Mine a specific block type. Use block IDs like DIAMOND_ORE, IRON_ORE, COAL_ORE, OAK_LOG, etc.")
    public String mineResource(
            @P("Block type to mine, e.g. DIAMOND_ORE, IRON_ORE, OAK_LOG") String blockType,
            @P("Maximum number of blocks to mine") int maxBlocks,
            @P("Optional search radius in blocks (default 64)") Integer radius) {
        log.info("Tool: mineResource({}, {}, {})", blockType, maxBlocks, radius);
        return withToolScope("mineResource", new InventoryIncreased(blockType, maxBlocks), () -> {
            jobTracker.setCurrentJob("Mining " + blockType + " (up to " + maxBlocks + ")", "unknown");
            int currentY = bot.getCurrentPosition().y();
            // TODO Sprint 13: pass targetY when MineStep exposes it
            BotAction action = BotAction.builder()
                    .type(BotAction.ActionType.MINE)
                    .target(blockType)
                    .build();
            SafetyValidator.ValidationResult safety = safetyValidator.validate(action, currentY);
            if (!safety.allowed()) {
                return safety.reason();
            }
            int searchRadius = radius != null ? radius : 64;
            var result = bot.mine(blockType, maxBlocks, searchRadius);
            return result.isSuccess()
                    ? String.format("Mining %s (up to %d blocks within %d radius)", blockType, maxBlocks, searchRadius)
                    : "Cannot mine: " + result.getMessage();
        });
    }

    @Tool("Remember the current location with a name and optional description")
    public String rememberLocation(
            @P("Name for this location") String name,
            @P("Optional description or tags") String description) {
        log.info("Tool: rememberLocation({}, {})", name, description);
        return withToolScope("rememberLocation", () -> {
            Location current = bot.getCurrentPosition();
            var saved = locationMemory.save(
                    name,
                    description,
                    current.x(),
                    current.y(),
                    current.z(),
                    LocationType.OTHER,
                    "unknown",
                    "player"
            );
            try {
                vectorMemory.store(saved);
            } catch (Exception e) {
                log.warn("Failed to index location '{}' in vector store", name, e);
            }
            return "Remembered location '" + name + "' at " + current;
        });
    }

    @Tool("Find a previously remembered location by name or description")
    public String findLocation(
            @P("Name or description to search for") String query) {
        log.info("Tool: findLocation({})", query);
        return withToolScope("findLocation", () -> {
            // Semantic vector search
            List<LocationMemoryEntry> vectorMatches = Collections.emptyList();
            try {
                vectorMatches = vectorMemory.searchByDescription(query, 10);
            } catch (Exception e) {
                log.warn("Vector search failed for query '{}'", query, e);
            }

            // SQL text search fallback / augmentation
            List<LocationMemoryEntry> sqlMatches = locationMemory.search(query);

            // Merge and deduplicate by ID, preserving order (vector first)
            Map<UUID, LocationMemoryEntry> merged = new LinkedHashMap<>();
            for (LocationMemoryEntry entry : vectorMatches) {
                if (entry.getId() != null) {
                    merged.putIfAbsent(entry.getId(), entry);
                }
            }
            for (LocationMemoryEntry entry : sqlMatches) {
                if (entry.getId() != null) {
                    merged.putIfAbsent(entry.getId(), entry);
                }
            }

            if (merged.isEmpty()) {
                return "No locations found matching '" + query + "'.";
            }

            Location current = bot.getCurrentPosition();
            return merged.values().stream()
                    .map(m -> {
                        double dist = Math.sqrt(
                                Math.pow(m.getX() - current.x(), 2) +
                                        Math.pow(m.getY() - current.y(), 2) +
                                        Math.pow(m.getZ() - current.z(), 2)
                        );
                        return String.format("%s: (%d, %d, %d) — %.0f blocks away",
                                m.getName(), m.getX(), m.getY(), m.getZ(), dist);
                    })
                    .collect(Collectors.joining("\n"));
        });
    }

    @Tool("Save a note for the current player")
    public String rememberNote(
            @P("The player's name") String playerName,
            @P("Note content to save") String content,
            @P("Optional comma-separated tags") String tags) {
        log.info("Tool: rememberNote({}, ...)", playerName);
        return withToolScope("rememberNote", () -> {
            playerNoteService.save(playerName, content, tags);
            return "Saved note for " + playerName + ".";
        });
    }

    @Tool("Recall notes for a player, optionally filtering by a query")
    public String recallNotes(
            @P("The player's name") String playerName,
            @P("Optional search query") String query) {
        log.info("Tool: recallNotes({}, {})", playerName, query);
        return withToolScope("recallNotes", () -> {
            var notes = (query == null || query.isBlank())
                    ? playerNoteService.findByPlayer(playerName)
                    : playerNoteService.search(playerName, query);
            if (notes.isEmpty()) {
                return "No notes found for " + playerName + ".";
            }
            return notes.stream()
                    .map(n -> "- " + n.getContent())
                    .collect(Collectors.joining("\n"));
        });
    }

    @Tool("Cancel the bot's current operation")
    public String cancelCurrentOperation() {
        log.info("Tool: cancelCurrentOperation");
        return withToolScope("cancelCurrentOperation", () -> {
            bot.cancel();
            jobTracker.clearJob();
            return "Cancelled current operation.";
        });
    }

    @Tool("Get the bot's current coordinates")
    public String getCurrentPosition() {
        return withToolScope("getCurrentPosition", () -> {
            Location pos = bot.getCurrentPosition();
            return "Current position: " + pos;
        });
    }

    @Tool("Get another player's current coordinates by name. Only works if the player is loaded in the world (within render distance).")
    public String getPlayerPosition(
            @P("The player's name to look up") String playerName) {
        log.info("Tool: getPlayerPosition({})", playerName);
        return withToolScope("getPlayerPosition", () -> {
            var pos = bot.getPlayerPosition(playerName);
            return pos.map(loc -> playerName + " is at " + loc)
                    .orElse("I can't see " + playerName + " right now. They may be out of range or offline.");
        });
    }

    @Tool("Locate a player by name and report their coordinates, distance, and direction. Only works if the player is loaded.")
    public String locatePlayer(@P("Player name") String playerName) {
        log.info("Tool: locatePlayer({})", playerName);
        return withToolScope("locatePlayer", () -> {
            PlayerInfo player = bot.findPlayer(playerName);
            if (player == null) {
                return "I can't see " + playerName + " right now. They may be out of range or offline.";
            }
            return String.format("Player %s is at %s, %.0f blocks %s",
                    player.getName(), player.getLocation(), player.getDistance(), player.getDirection());
        });
    }

    @Tool("List all nearby players within a radius, with coordinates, distance, and direction")
    public String scanForPlayers(@P("Search radius in blocks") int radius) {
        log.info("Tool: scanForPlayers({})", radius);
        return withToolScope("scanForPlayers", () -> {
            List<PlayerInfo> players = bot.getNearbyPlayers(radius);
            if (players.isEmpty()) {
                return "No players found within " + radius + " blocks.";
            }
            return players.stream()
                    .map(p -> String.format("Player %s is at %s, %.0f blocks %s",
                            p.getName(), p.getLocation(), p.getDistance(), p.getDirection()))
                    .collect(Collectors.joining("\n"));
        });
    }

    @Tool("Scan for nearby mobs or animals of a specific type. Entity type examples: Creeper, Zombie, Pig, Cow, Skeleton, Spider.")
    public String scanForEntities(@P("Entity type, e.g. Creeper, Zombie, Pig, Cow") String entityType,
                                  @P("Search radius in blocks") int radius) {
        log.info("Tool: scanForEntities({}, {})", entityType, radius);
        return withToolScope("scanForEntities", () -> {
            List<EntityInfo> entities = bot.getNearbyEntities(entityType, radius);
            if (entities.isEmpty()) {
                return "No " + entityType + " found within " + radius + " blocks.";
            }
            return entities.stream()
                    .map(e -> String.format("%s at %s, %.0f blocks %s",
                            e.getType(), e.getLocation(), e.getDistance(), e.getDirection()))
                    .collect(Collectors.joining("\n"));
        });
    }

    @Tool("Navigate to surface X,Z coordinates. The bot will find any Y level to reach the target.")
    public String navigateToSurface(
            @P("X coordinate") int x,
            @P("Z coordinate") int z) {
        log.info("Tool: navigateToSurface({}, {})", x, z);
        return withToolScope("navigateToSurface", () -> {
            jobTracker.setCurrentJob("Navigating to surface (" + x + ", " + z + ")", "unknown");
            var result = bot.navigateToXZ(x, z);
            if (result.isSuccess()) {
                chatService.send("Navigating to surface coordinates (" + x + ", " + z + ")");
                return "Navigating to surface (" + x + ", " + z + ")";
            }
            return "Cannot navigate: " + result.getMessage();
        });
    }

    @Tool("Go to a specific Y level. Useful for strip mining at a particular depth.")
    public String goToDepth(
            @P("Target Y level") int y) {
        log.info("Tool: goToDepth({})", y);
        return withToolScope("goToDepth", () -> {
            jobTracker.setCurrentJob("Going to Y=" + y, "unknown");
            var result = bot.navigateToYLevel(y);
            if (result.isSuccess()) {
                chatService.send("Going to Y=" + y);
                return "Going to Y=" + y;
            }
            return "Cannot navigate: " + result.getMessage();
        });
    }

    @Tool("Explore within a radius of a center point. The bot will path to a random point inside the area.")
    public String exploreArea(
            @P("Center X") int x,
            @P("Center Y") int y,
            @P("Center Z") int z,
            @P("Radius in blocks") int radius) {
        log.info("Tool: exploreArea({}, {}, {}, {})", x, y, z, radius);
        return withToolScope("exploreArea", () -> {
            jobTracker.setCurrentJob("Exploring within " + radius + " blocks of (" + x + ", " + y + ", " + z + ")", "unknown");
            var center = new BotOperations.Location(x, y, z);
            var result = bot.exploreNear(center, radius);
            if (result.isSuccess()) {
                chatService.send("Exploring within " + radius + " blocks of (" + x + ", " + y + ", " + z + ")");
                return "Exploring within " + radius + " blocks of (" + x + ", " + y + ", " + z + ")";
            }
            return "Cannot explore: " + result.getMessage();
        });
    }

    @Tool("Flee from specific coordinates to maintain a safe distance. Use when threats are nearby.")
    public String fleeFrom(
            @P("Threat X") int x,
            @P("Threat Y") int y,
            @P("Threat Z") int z,
            @P("Safe distance in blocks") int distance) {
        log.info("Tool: fleeFrom({}, {}, {}, {})", x, y, z, distance);
        return withToolScope("fleeFrom", () -> {
            jobTracker.setCurrentJob("Fleeing from (" + x + ", " + y + ", " + z + ")", "unknown");
            var threat = new BotOperations.Location(x, y, z);
            var result = bot.fleeFrom(threat, distance);
            if (result.isSuccess()) {
                chatService.send("Fleeing to maintain " + distance + " blocks from threat");
                return "Fleeing from (" + x + ", " + y + ", " + z + "), maintaining " + distance + " blocks";
            }
            return "Cannot flee: " + result.getMessage();
        });
    }

    @Tool("Navigate to the nearest of multiple remembered locations by name. Provide a comma-separated list of location names.")
    public String navigateToNearestLocation(
            @P("Comma-separated location names") String locationNames) {
        log.info("Tool: navigateToNearestLocation({})", locationNames);
        return withToolScope("navigateToNearestLocation", () -> {
            jobTracker.setCurrentJob("Navigating to nearest of: " + locationNames, "unknown");
            String[] names = locationNames.split(",");
            List<BotOperations.Location> candidates = new ArrayList<>();
            for (String name : names) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                var found = locationMemory.findByName(trimmed);
                if (found.isPresent()) {
                    var loc = found.get();
                    candidates.add(new BotOperations.Location(loc.getX(), loc.getY(), loc.getZ()));
                }
            }
            if (candidates.isEmpty()) {
                return "None of the specified locations were found: " + locationNames;
            }
            var result = bot.navigateToNearest(candidates);
            if (result.isSuccess()) {
                chatService.send("Navigating to nearest of " + locationNames);
                return "Navigating to nearest of " + locationNames;
            }
            return "Cannot navigate: " + result.getMessage();
        });
    }

    @Tool("Send a message to the player chat. Use this to report progress, ask questions, or confirm actions during multi-step tasks.")
    public String sendMessage(
            @P("Message text to send to the player") String message) {
        log.info("Tool: sendMessage({})", message);
        return withToolScope("sendMessage", () -> {
            chatService.send(message);
            return "Sent: " + message;
        });
    }

    @Tool("Enable or disable safe mode. Safe mode prevents block breaking, disables parkour/sprint, enables mob avoidance, and allows door usage.")
    public String setSafetyMode(@P("true to enable safe mode, false for normal") boolean enabled) {
        log.info("Tool: setSafetyMode({})", enabled);
        return withToolScope("setSafetyMode", () -> {
            bot.setSafetyMode(enabled);
            if (enabled) {
                chatService.send("Safe mode enabled. I'll be careful.");
                return "Safe mode enabled";
            } else {
                chatService.send("Safe mode disabled. Normal behavior restored.");
                return "Safe mode disabled";
            }
        });
    }

    @Tool("Report the bot's current health, hunger, armor, and any nearby threats.")
    public String getStatusReport() {
        log.info("Tool: getStatusReport");
        return withToolScope("getStatusReport", () -> {
            var health = bot.getHealthStatus();
            var threats = bot.getNearbyThreats(32);
            String threatStr = threats.isEmpty()
                    ? "none"
                    : threats.stream().map(Object::toString).collect(Collectors.joining(", "));
            return "Status: " + health + ". Nearby threats: " + threatStr;
        });
    }

    @Tool("Set pathing behavior mode. 'careful' avoids breaking blocks and uses doors. 'aggressive' allows breaking for speed. 'default' restores normal settings.")
    public String setPathingBehavior(@P("Behavior mode: careful, aggressive, or default") String mode) {
        log.info("Tool: setPathingBehavior({})", mode);
        return withToolScope("setPathingBehavior", () -> {
            String normalized = mode.toLowerCase();
            if (!normalized.equals("careful") && !normalized.equals("aggressive") && !normalized.equals("default")) {
                return "Invalid mode: " + mode + ". Use careful, aggressive, or default.";
            }
            bot.setPathingBehavior(normalized);
            chatService.send("Pathing behavior set to " + normalized);
            return "Pathing behavior set to " + normalized;
        });
    }

    @Tool("Add a block type to the avoid-breaking list. The bot will path around these blocks instead of breaking them. Examples: minecraft:glass, minecraft:oak_planks")
    public String avoidBreakingBlock(@P("Block ID to avoid breaking, e.g. minecraft:glass") String blockType) {
        log.info("Tool: avoidBreakingBlock({})", blockType);
        return withToolScope("avoidBreakingBlock", () -> {
            bot.addBlockToAvoid(blockType);
            return "Added " + blockType + " to avoid-breaking list";
        });
    }

    @Tool("Clear all custom block avoidance rules and restore defaults.")
    public String clearBlockAvoidance() {
        log.info("Tool: clearBlockAvoidance");
        return withToolScope("clearBlockAvoidance", () -> {
            bot.clearAvoidedBlocks();
            return "Cleared all block avoidance rules.";
        });
    }

    @Tool("Check if the bot has at least a given count of an item in inventory. Use item IDs like minecraft:cobblestone, minecraft:diamond, minecraft:bread.")
    public String checkInventory(
            @P("Item ID, e.g. minecraft:cobblestone") String itemId,
            @P("Minimum count needed") int count) {
        log.info("Tool: checkInventory({}, {})", itemId, count);
        return withToolScope("checkInventory", () -> {
            boolean has = bot.hasItem(itemId, count);
            int actual = bot.countItem(itemId);
            if (has) {
                chatService.send("You have " + actual + " " + itemId + " (need " + count + ")");
                return "You have " + actual + " " + itemId + " (need " + count + ")";
            }
            return "You only have " + actual + " " + itemId + " (need " + count + ")";
        });
    }

    @Tool("Get a summary of the bot's inventory contents")
    public String getInventorySummary() {
        log.info("Tool: getInventorySummary");
        return withToolScope("getInventorySummary", () -> bot.getInventorySummary());
    }

    @Tool("Build a filled rectangular area with a specific block type. Provide two opposite corners. The bot must have enough blocks in inventory.")
    public String buildArea(
            @P("Corner 1 X") int x1,
            @P("Corner 1 Y") int y1,
            @P("Corner 1 Z") int z1,
            @P("Corner 2 X") int x2,
            @P("Corner 2 Y") int y2,
            @P("Corner 2 Z") int z2,
            @P("Block type, e.g. minecraft:oak_planks") String blockType) {
        log.info("Tool: buildArea({}, {}, {}, {}, {}, {}, {})", x1, y1, z1, x2, y2, z2, blockType);
        return withToolScope("buildArea", new InventoryDecreased(blockType, 1), () -> {
            jobTracker.setCurrentJob("Building " + blockType + " area", "unknown");
            int currentY = bot.getCurrentPosition().y();
            BotAction safetyAction = BotAction.builder()
                    .type(BotAction.ActionType.BUILD)
                    .target(blockType)
                    .build();
            SafetyValidator.ValidationResult safety = safetyValidator.validate(safetyAction, currentY);
            if (!safety.allowed()) {
                return safety.reason();
            }
            int width = Math.abs(x2 - x1) + 1;
            int height = Math.abs(y2 - y1) + 1;
            int length = Math.abs(z2 - z1) + 1;
            int volume = width * height * length;

            // Material verification
            boolean hasMaterials = bot.hasItem(blockType, volume);
            if (!hasMaterials) {
                int actual = bot.countItem(blockType);
                chatService.send("Not enough " + blockType + ". Need " + volume + ", have " + actual);
                return "Not enough " + blockType + ". Need " + volume + " blocks, only have " + actual;
            }

            var result = bot.buildPlatform(x1, y1, z1, x2, y2, z2, blockType);
            if (result.isSuccess()) {
                chatService.send("Building " + blockType + " area " + width + "x" + height + "x" + length);
                return "Building " + blockType + " area " + width + "x" + height + "x" + length
                        + " at (" + x1 + ", " + y1 + ", " + z1 + ") to (" + x2 + ", " + y2 + ", " + z2 + ")";
            }
            return "Cannot build: " + result.getMessage();
        });
    }

    // -------------------------------------------------------------------------
    // Survival / economy tools (Sprint 12 C3)
    // -------------------------------------------------------------------------

    @Tool("Eat food from the bot's inventory. Pass an itemId like 'minecraft:bread' or pass null to pick the best available food. Returns a status message.")
    public String eatFood(@P("Minecraft item id, or null for auto-best") String itemId) {
        log.info("Tool: eatFood({})", itemId);
        return withToolScope("eatFood", () -> {
            var health = bot.getHealthStatus();
            boolean ate = bot.eat(itemId);
            if (!ate) {
                String missing = (itemId == null) ? "any food" : itemId;
                return "Could not eat — no " + missing + " in inventory or hunger is already full.";
            }
            var healthAfter = bot.getHealthStatus();
            String label = (itemId == null) ? "food" : itemId;
            return "Ate " + label + ". Food: " + healthAfter.foodLevel() + "/20.";
        });
    }

    @Tool("Place a torch at the bot's current location if there is a suitable adjacent surface and a torch is in inventory.")
    public String placeTorchAtFeet() {
        log.info("Tool: placeTorchAtFeet");
        return withToolScope("placeTorchAtFeet", new InventoryDecreased("minecraft:torch", 1), () -> {
            if (!bot.hasItem("minecraft:torch", 1)) {
                return "No torch in inventory.";
            }
            bot.placeTorch();
            return "Torch placed.";
        });
    }

    @Tool("Get the current world time in ticks (0-23999). 0=dawn, 6000=noon, 12000=dusk, 18000=midnight.")
    public String getWorldTime() {
        log.info("Tool: getWorldTime");
        return withToolScope("getWorldTime", () -> {
            int time = bot.getWorldTime();
            String period = bot.isNight() ? "night" : "day";
            return "World time: " + time + " (" + period + ").";
        });
    }

    @Tool("Is it currently night-time in the world?")
    public String isNight() {
        log.info("Tool: isNight");
        return withToolScope("isNight", () -> {
            boolean night = bot.isNight();
            int time = bot.getWorldTime();
            return night ? "Yes, it is night (time: " + time + ")." : "No, it is daytime (time: " + time + ").";
        });
    }

    @Tool("Get the light level at the bot's current position (0-15). Low values mean mob spawning is possible.")
    public String getLightLevel() {
        log.info("Tool: getLightLevel");
        return withToolScope("getLightLevel", () -> {
            int light = bot.getLightLevel();
            String risk = (light < 8) ? " (mob spawning possible)" : " (safe)";
            return "Light level: " + light + risk + ".";
        });
    }

    @Tool("Walk over and pick up any item entities within the given radius.")
    public String pickupNearbyItems(@P("Radius in blocks") int radius) {
        log.info("Tool: pickupNearbyItems({})", radius);
        return withToolScope("pickupNearbyItems", () -> bot.pickupNearbyItems(radius));
    }

    // -------------------------------------------------------------------------
    // Plan submission (Sprint 13 C1)
    // -------------------------------------------------------------------------

    @Tool("""
            Submit a structured multi-step Plan for the bot to execute. Use this for tasks requiring
            more than one tool call or that should run for an extended time. The Plan is a sequence
            of typed Steps (NavigateStep, MineStep, WaitForStep).
            Single-step tasks ("come here", "what is in your inventory") should NOT use submitPlan.
            """)
    public String submitPlan(
            @P("Brief description of what this plan does") String summary,
            @P("""
               JSON array of steps. Each step has a "type" field (Navigate, Mine, or WaitFor)
               plus step-specific args and optional "timeout" (ISO-8601, e.g. "PT10M") and
               "retry" ({\"maxRetries\":1,\"backoff\":\"PT2S\"}).
               Navigate: {"type":"Navigate","x":100,"y":64,"z":200}
               Mine: {"type":"Mine","blockType":"minecraft:iron_ore","quantity":16,"searchRadius":32}
               WaitFor: {"type":"WaitFor","condition":{"type":"InventoryHas","itemId":"minecraft:iron_ore","count":16},"timeout":"PT5M"}
               """) String stepsJson
    ) {
        log.info("Tool: submitPlan(summary={}, steps={})", summary, stepsJson);
        return withToolScope("submitPlan", () -> {
            List<Step> steps;
            try {
                steps = PlanSerializer.stepsFromJson(stepsJson);
            } catch (IllegalArgumentException e) {
                return "Invalid steps JSON: " + e.getMessage();
            }

            if (steps.isEmpty()) {
                return "Cannot submit an empty plan.";
            }

            Plan plan = new Plan(
                    UUID.randomUUID(),
                    summary,
                    "player",               // Q3 (approval gate) will wire actual player name — deferred
                    Instant.now(),
                    steps,
                    TerminationPolicy.DEFAULT);

            try {
                var snapshot = taskExecutor.submit(plan);
                chatService.send("Plan submitted: " + summary + " (" + steps.size() + " steps)");
                return "Plan submitted (id=" + snapshot.id() + "): " + summary + " — " + steps.size() + " steps";
            } catch (IllegalStateException e) {
                return "Cannot submit plan — another plan is already running. Cancel it first with cancelCurrentOperation().";
            }
        });
    }

    @Tool("Get the current status of the active plan, if any.")
    public String getPlanStatus() {
        log.info("Tool: getPlanStatus");
        return withToolScope("getPlanStatus", () -> {
            var snap = taskExecutor.currentTask();
            if (snap.isEmpty()) {
                return "No active plan.";
            }
            var s = snap.get();
            Plan plan = s.plan();
            int total = plan.steps().size();
            int step = s.currentStepIndex() + 1;
            String progress = s.stepProgress() != null && !s.stepProgress().isEmpty()
                    ? " | " + s.stepProgress() : "";
            return String.format("Plan: %s | Step %d/%d: %s | State: %s%s",
                    plan.summary(), step, total,
                    plan.steps().get(s.currentStepIndex()).description(),
                    s.state(), progress);
        });
    }

    @Tool("Cancel the currently running plan.")
    public String cancelPlan() {
        log.info("Tool: cancelPlan");
        return withToolScope("cancelPlan", () -> {
            var snap = taskExecutor.currentTask();
            if (snap.isEmpty()) {
                return "No active plan to cancel.";
            }
            boolean cancelled = taskExecutor.cancel(snap.get().id());
            if (cancelled) {
                chatService.send("Plan cancelled: " + snap.get().plan().summary());
                return "Plan cancelled: " + snap.get().plan().summary();
            }
            return "Could not cancel plan — it may have already finished.";
        });
    }

    // -------------------------------------------------------------------------
    // Planner integration (Sprint 13 A2)
    // -------------------------------------------------------------------------

    @Tool("""
            Decompose a multi-step player request into a Plan and execute it.
            Use this for complex requests requiring multiple actions in sequence.
            The Plan is built by the planner role and submitted to the task executor.
            Single-step actions (come here, what is my position) should NOT use this — call the specific tool directly.
            """)
    public String decomposeAndExecute(
            @P("The player's request in their original words. Example: 'mine 64 iron ore'.") String playerRequest) {
        log.info("Tool: decomposeAndExecute({})", playerRequest);
        return withToolScope("decomposeAndExecute", new NoOp(), () -> {
            try {
                PlanDraft draft;
                try {
                    draft = planner.propose(playerRequest);
                } catch (RuntimeException e) {
                    log.warn("Planner LLM call failed, attempting one retry: {}", e.getMessage());
                    try {
                        draft = planner.propose(playerRequest);
                    } catch (RuntimeException e2) {
                        return "Planner failed to produce a valid plan. Try rephrasing or use individual tools.";
                    }
                }
                Plan plan = PlanFromDraftConverter.convert(draft, "player");

                if (approvalGate.requiresApproval(plan)) {
                    String prefix = pendingApprovalStore.peek().isPresent()
                            ? "Replacing previous pending plan. "
                            : "";
                    pendingApprovalStore.set(plan);
                    String preview = prefix + formatPlanPreview(plan);
                    chatService.send(preview);
                    log.info("Plan held for approval: {} ({} steps)", plan.summary(), plan.steps().size());
                    return preview;
                }

                TaskSnapshot snapshot = taskExecutor.submit(plan);
                chatService.send("Plan submitted: " + plan.summary() + " (" + plan.steps().size() + " steps)");
                return "Plan submitted (id=" + snapshot.id() + "): " + plan.summary()
                        + " — " + plan.steps().size() + " steps";
            } catch (PlanValidationException e) {
                log.warn("Plan rejected for request '{}': {}", playerRequest, e.getMessage());
                return "Plan rejected: " + e.getMessage();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Routine tools (Sprint 14 A4)
    // -------------------------------------------------------------------------

    @Tool("Invoke a previously defined Routine by name with parameters. Routines are parameterized Plan templates " +
            "(e.g. 'returnHome' takes baseX/baseY/baseZ).")
    public String submitRoutine(
            @P("The routine name (e.g. 'returnHome', 'mineUntilFull')") String name,
            @P("JSON map of parameter values, e.g. {\"baseX\":100,\"baseY\":64,\"baseZ\":200}") String paramsJson
    ) {
        log.info("Tool: submitRoutine({}, {})", name, paramsJson);
        return withToolScope("submitRoutine", new NoOp(), () -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = JACKSON.readValue(paramsJson, Map.class);
                Plan plan = routineService.invoke(name, args);

                if (approvalGate.requiresApproval(plan)) {
                    pendingApprovalStore.set(plan);
                    String preview = formatPlanPreview(plan);
                    chatService.send(preview);
                    return "Routine '" + name + "' loaded — awaiting approval. " + preview;
                }

                TaskSnapshot snapshot = taskExecutor.submit(plan);
                chatService.send("Routine '" + name + "' started.");
                return "Routine '" + name + "' started (id=" + snapshot.id() + ").";

            } catch (RoutineNotFoundException e) {
                List<String> available = routineService.listAll().stream()
                        .map(RoutineDefinition::getName)
                        .toList();
                return "Unknown routine: " + name + ". Available: " + available;
            } catch (IllegalStateException e) {
                return "Cannot start routine — another plan is already running. Cancel it first.";
            } catch (Exception e) {
                return "Routine invocation failed: " + e.getMessage();
            }
        });
    }

    @Tool("Define a new Routine — a parameterized Plan template the bot can re-invoke later by name.")
    public String defineRoutine(
            @P("Routine name (camelCase, e.g. 'gatherWood')") String name,
            @P("One-line description of what this routine does") String description,
            @P("Plan template as JSON array of steps with ${param} placeholders for arguments. " +
                    "Example: [{\"type\":\"Navigate\",\"x\":\"${x}\",\"y\":\"${y}\",\"z\":\"${z}\"}]") String planTemplate,
            @P("JSON array of parameter names this routine expects, e.g. [\"x\",\"y\",\"z\"]") String parametersJson
    ) {
        log.info("Tool: defineRoutine({}, ...)", name);
        return withToolScope("defineRoutine", () -> {
            try {
                @SuppressWarnings("unchecked")
                List<String> parameters = JACKSON.readValue(parametersJson, List.class);
                UUID id = routineService.define(name, description, planTemplate, parameters);
                return "Routine '" + name + "' defined (id=" + id + "). "
                        + "Invoke it with: submitRoutine(\"" + name + "\", {params}).";
            } catch (IllegalArgumentException e) {
                return "Failed to define routine: " + e.getMessage();
            } catch (Exception e) {
                return "Unexpected error defining routine: " + e.getMessage();
            }
        });
    }

    @Tool("List all defined Routines available to invoke.")
    public String listRoutines() {
        log.info("Tool: listRoutines");
        return withToolScope("listRoutines", () -> {
            List<RoutineDefinition> all = routineService.listAll();
            if (all.isEmpty()) {
                return "No routines defined.";
            }
            return all.stream()
                    .map(r -> r.getName() + " — " + r.getDescription())
                    .collect(Collectors.joining("\n"));
        });
    }

    // -------------------------------------------------------------------------
    // Correction memory (Sprint 15 Q4)
    // -------------------------------------------------------------------------

    @Tool("Remember a player correction. Call this when the player corrects, disagrees, or expresses " +
          "dissatisfaction with an action. Captures what was wrong and what should happen instead " +
          "so future tasks can avoid the same mistake.")
    public String rememberCorrection(
            @P("Player name") String player,
            @P("What you were doing when corrected (short context)") String context,
            @P("What was wrong (the player's words)") String wrongAction,
            @P("What should happen instead (the corrected behavior)") String correctApproach) {
        log.info("Tool: rememberCorrection({}, ...)", player);
        return withToolScope("rememberCorrection", new NoOp(), () -> {
            String content = String.format(
                    "Player %s corrected: while %s, %s. Correct approach: %s",
                    player, context, wrongAction, correctApproach);
            episodicMemoryService.writeWithImportance(
                    "correction", content, sessionContext.getSessionId(), Optional.empty(), 9);
            counters.inc("memory.corrections");
            runLog.log("correction_saved", Map.of(
                    "player", player,
                    "wrong", wrongAction,
                    "correct", correctApproach));
            return "Noted. I'll remember not to " + shorten(wrongAction, 80) + ".";
        });
    }

    /**
     * Truncate a string to {@code maxLen} characters, appending {@code "…"} if trimmed.
     */
    private static String shorten(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    /**
     * Build a compact plan preview string for the player.
     *
     * <p>Lists each step with a 1-based index, the total estimated wall-clock time,
     * and a prompt to start with {@code agent go}. The result is truncated to 240
     * characters to fit within Minecraft's signed-chat payload limit.</p>
     *
     * @param plan the plan awaiting approval
     * @return a human-readable preview string, at most 240 characters
     */
    String formatPlanPreview(Plan plan) {
        StringBuilder sb = new StringBuilder("Plan: ").append(plan.summary()).append("\n");
        for (int i = 0; i < plan.steps().size(); i++) {
            sb.append("  ").append(i + 1).append(". ")
              .append(plan.steps().get(i).description()).append("\n");
        }
        long estSec = plan.steps().stream()
                .mapToLong(s -> s.timeout() != null ? s.timeout().getSeconds() : 0L)
                .sum();
        sb.append("Est. time: ~").append(estSec / 60).append("m. Reply 'agent go' to start.");
        String full = sb.toString();
        return full.length() > 240 ? full.substring(0, 240) : full;
    }

    // -------------------------------------------------------------------------
    // Instrumentation helpers
    // -------------------------------------------------------------------------

    /**
     * Run {@code action} inside an MDC scope that sets {@code tool=<toolName>}, timing the
     * call and recording success/error counters and a structured RunLog event.
     *
     * <p>Existing {@link JobTracker} calls inside each tool body are preserved — this helper
     * does not replace them; it wraps around the overall method body so job tracking is
     * still the first thing that happens after the MDC scope opens.</p>
     */
    private <T> T withToolScope(String toolName, Supplier<T> action) {
        return withToolScope(toolName, new NoOp(), action);
    }

    /**
     * Post-condition variant of {@link #withToolScope(String, Supplier)}.
     *
     * <p>For synchronous tools (see {@link #ASYNC_TOOLS}), captures before/after snapshots
     * and emits an immediate {@code <framework>} message describing the observed delta.
     * For async tools (Baritone-backed {@code mineResource}, {@code navigateTo},
     * {@code buildArea}), the world state has not changed by the time the tool returns —
     * only an "expected: ... (async, verification pending)" notice is emitted so the LLM is
     * not told an incorrect observed count. {@link NoOp} post-conditions are silently skipped.</p>
     *
     * @param toolName tool name for logging and counter keys
     * @param pc       expected post-condition; use {@link NoOp} to opt out of checking
     * @param action   the tool body to execute
     */
    private <T> T withToolScope(String toolName, PostCondition pc, Supplier<T> action) {
        boolean isAsync = !(pc instanceof NoOp) && ASYNC_TOOLS.contains(toolName);

        try (MDC.MDCCloseable ignored = MDC.putCloseable("tool", toolName)) {
            counters.inc("tool." + toolName + ".calls");
            long start = System.currentTimeMillis();
            try {
                WorldStateSnapshot before = (pc instanceof NoOp || isAsync) ? null : captureSnapshot(pc);
                T result = action.get();
                long durationMs = System.currentTimeMillis() - start;
                counters.inc("tool." + toolName + ".success");
                runLog.log("tool_call", Map.of("tool", toolName, "duration_ms", durationMs));
                if (!(pc instanceof NoOp)) {
                    if (isAsync) {
                        emitAsyncPostConditionNotice(toolName, pc);
                    } else {
                        emitPostConditionResult(toolName, pc, before);
                    }
                }
                return result;
            } catch (RuntimeException e) {
                long durationMs = System.currentTimeMillis() - start;
                counters.inc("tool." + toolName + ".errors");
                runLog.log("tool_error", Map.of(
                        "tool", toolName,
                        "duration_ms", durationMs,
                        "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                throw e;
            }
        }
    }

    /**
     * Capture the world state attributes relevant to evaluating {@code pc}.
     * For inventory post-conditions the relevant item ID is queried; for position
     * post-conditions only the position is captured; for {@link NoOp} this must
     * not be called.
     */
    private WorldStateSnapshot captureSnapshot(PostCondition pc) {
        BotOperations.Location pos = bot.getCurrentPosition();
        Map<String, Integer> counts = switch (pc) {
            case InventoryIncreased(var id, var ignored) -> Map.of(id, bot.countItem(id));
            case InventoryDecreased(var id, var ignored) -> Map.of(id, bot.countItem(id));
            case PositionWithin ignored -> Map.of();
            case NoOp ignored -> Map.of();
        };
        return new WorldStateSnapshot(pos, counts);
    }

    /**
     * Emit a "verification pending" notice for async tools.
     *
     * <p>Called when the tool dispatches work to Baritone asynchronously. The world state
     * will not reflect the expected change until Baritone finishes — capturing an "after"
     * snapshot now would always show observed=0 and report a false failure.</p>
     */
    private void emitAsyncPostConditionNotice(String toolName, PostCondition pc) {
        try {
            String message = "tool:" + toolName + " done. expected: " + pc.describe()
                    + " (async, verification pending).";
            langChain4jService.addFrameworkContext(message);
        } catch (Exception e) {
            log.warn("Failed to emit async post-condition notice for tool '{}': {}", toolName, e.getMessage());
        }
    }

    /**
     * Capture a post-execution snapshot and emit a {@code <framework>} message
     * describing whether the post-condition was satisfied.
     */
    private void emitPostConditionResult(String toolName, PostCondition pc, WorldStateSnapshot before) {
        try {
            WorldStateSnapshot after = captureSnapshot(pc);
            boolean satisfied = PostConditionChecker.check(pc, before, after);
            String message = buildFrameworkMessage(toolName, pc, before, after, satisfied);
            langChain4jService.addFrameworkContext(message);
        } catch (Exception e) {
            log.warn("Post-condition check failed for tool '{}': {}", toolName, e.getMessage());
        }
    }

    /**
     * Build the human-readable framework message for the post-condition result.
     *
     * <p>Format examples:
     * <pre>
     *   tool:mineResource done. expected: +4 minecraft:iron_ore. observed: +4.
     *   tool:mineResource done. expected: +4 minecraft:iron_ore. observed: +3 (1 short).
     *   tool:navigateTo done. expected: position within 3 of (100,64,200). observed: satisfied.
     *   tool:placeBlockAt done. expected: -1 minecraft:torch. observed: -1.
     * </pre>
     */
    private String buildFrameworkMessage(String toolName, PostCondition pc,
                                          WorldStateSnapshot before, WorldStateSnapshot after,
                                          boolean satisfied) {
        String prefix = "tool:" + toolName + " done. expected: " + pc.describe() + ". observed: ";
        return switch (pc) {
            case InventoryIncreased(var id, var n) -> {
                int delta = after.inventoryCounts().getOrDefault(id, 0)
                        - before.inventoryCounts().getOrDefault(id, 0);
                if (satisfied) {
                    yield prefix + "+" + delta + ".";
                }
                int shortage = n - delta;
                yield prefix + "+" + delta + " (" + shortage + " short).";
            }
            case InventoryDecreased(var id, var n) -> {
                int delta = before.inventoryCounts().getOrDefault(id, 0)
                        - after.inventoryCounts().getOrDefault(id, 0);
                if (satisfied) {
                    yield prefix + "-" + delta + ".";
                }
                int shortage = n - delta;
                yield prefix + "-" + delta + " (" + shortage + " short).";
            }
            case PositionWithin ignored ->
                    prefix + (satisfied ? "satisfied." : "not satisfied.");
            case NoOp ignored -> prefix + "done.";
        };
    }

    @Tool("Place a single block at specific coordinates. The bot must have the block in inventory.")
    public String placeBlockAt(
            @P("X coordinate") int x,
            @P("Y coordinate") int y,
            @P("Z coordinate") int z,
            @P("Block type, e.g. minecraft:torch") String blockType) {
        log.info("Tool: placeBlockAt({}, {}, {}, {})", x, y, z, blockType);
        return withToolScope("placeBlockAt", new InventoryDecreased(blockType, 1), () -> {
            jobTracker.setCurrentJob("Placing " + blockType + " at (" + x + ", " + y + ", " + z + ")", "unknown");
            int currentY = bot.getCurrentPosition().y();
            BotAction safetyAction = BotAction.builder()
                    .type(BotAction.ActionType.BUILD)
                    .target(blockType)
                    .build();
            SafetyValidator.ValidationResult safety = safetyValidator.validate(safetyAction, currentY);
            if (!safety.allowed()) {
                return safety.reason();
            }
            boolean hasMaterials = bot.hasItem(blockType, 1);
            if (!hasMaterials) {
                chatService.send("No " + blockType + " in inventory");
                return "No " + blockType + " in inventory to place";
            }
            var result = bot.placeBlock(x, y, z, blockType);
            if (result.isSuccess()) {
                chatService.send("Placed " + blockType + " at (" + x + ", " + y + ", " + z + ")");
                return "Placed " + blockType + " at (" + x + ", " + y + ", " + z + ")";
            }
            return "Cannot place block: " + result.getMessage();
        });
    }
}
