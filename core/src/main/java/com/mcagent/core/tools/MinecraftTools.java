package com.mcagent.core.tools;

import com.mcagent.core.memory.LocationMemoryEntry;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.LocationType;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
import com.mcagent.core.model.BotAction;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.SafetyValidator;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Tools exposed to the LLM for Minecraft interactions.
 * These methods are discovered by LangChain4j and presented to the model.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinecraftTools {

    private final BotOperations bot;
    private final LocationMemoryService locationMemory;
    private final VectorMemoryService vectorMemory;
    private final PlayerNoteService playerNoteService;
    private final ChatService chatService;
    private final SafetyValidator safetyValidator;
    private final JobTracker jobTracker;
    private final Counters counters;
    private final RunLog runLog;

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
        return withToolScope("navigateTo", () -> {
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
        return withToolScope("mineResource", () -> {
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
        return withToolScope("buildArea", () -> {
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
        return withToolScope("placeTorchAtFeet", () -> {
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
    // Instrumentation helper
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
        try (MDC.MDCCloseable ignored = MDC.putCloseable("tool", toolName)) {
            counters.inc("tool." + toolName + ".calls");
            long start = System.currentTimeMillis();
            try {
                T result = action.get();
                long durationMs = System.currentTimeMillis() - start;
                counters.inc("tool." + toolName + ".success");
                runLog.log("tool_call", Map.of("tool", toolName, "duration_ms", durationMs));
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

    @Tool("Place a single block at specific coordinates. The bot must have the block in inventory.")
    public String placeBlockAt(
            @P("X coordinate") int x,
            @P("Y coordinate") int y,
            @P("Z coordinate") int z,
            @P("Block type, e.g. minecraft:torch") String blockType) {
        log.info("Tool: placeBlockAt({}, {}, {}, {})", x, y, z, blockType);
        return withToolScope("placeBlockAt", () -> {
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
