package com.mcagent.core.tools;

import com.mcagent.core.memory.LocationMemoryEntry;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.LocationType;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.ChatService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
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

    @Tool("Follow a specific player by name. The bot will path to and continue following them.")
    public String followPlayer(
            @P("The player's name to follow") String playerName) {
        log.info("Tool: followPlayer({})", playerName);
        var result = bot.followPlayer(playerName);
        if (result.isSuccess()) {
            chatService.send("Following player " + playerName);
            return "Following player " + playerName;
        }
        return "Cannot follow: " + result.getMessage();
    }

    @Tool("Navigate to specific X Y Z coordinates")
    public String navigateTo(
            @P("X coordinate") int x,
            @P("Y coordinate") int y,
            @P("Z coordinate") int z) {
        log.info("Tool: navigateTo({}, {}, {})", x, y, z);
        var result = bot.navigateTo(x, y, z);
        if (result.isSuccess()) {
            chatService.send("Navigating to (" + x + ", " + y + ", " + z + ")");
            return "Navigating to (" + x + ", " + y + ", " + z + ")";
        }
        return "Cannot navigate: " + result.getMessage();
    }

    @Tool("Navigate to a previously remembered location by name")
    public String navigateToLocation(
            @P("Name of the remembered location") String locationName) {
        log.info("Tool: navigateToLocation({})", locationName);
        var found = locationMemory.findByName(locationName);
        if (found.isEmpty()) {
            return "I don't remember a location named '" + locationName + "'.";
        }
        var loc = found.get();
        var result = bot.navigateTo(loc.getX(), loc.getY(), loc.getZ());
        return result.isSuccess()
                ? "Navigating to '" + locationName + "' at (" + loc.getX() + ", " + loc.getY() + ", " + loc.getZ() + ")"
                : "Cannot navigate to '" + locationName + "': " + result.getMessage();
    }

    @Tool("Mine a specific block type. Use block IDs like DIAMOND_ORE, IRON_ORE, COAL_ORE, OAK_LOG, etc.")
    public String mineResource(
            @P("Block type to mine, e.g. DIAMOND_ORE, IRON_ORE, OAK_LOG") String blockType,
            @P("Maximum number of blocks to mine") int maxBlocks,
            @P("Optional search radius in blocks (default 64)") Integer radius) {
        log.info("Tool: mineResource({}, {}, {})", blockType, maxBlocks, radius);
        int searchRadius = radius != null ? radius : 64;
        var result = bot.mine(blockType, maxBlocks, searchRadius);
        return result.isSuccess()
                ? String.format("Mining %s (up to %d blocks within %d radius)", blockType, maxBlocks, searchRadius)
                : "Cannot mine: " + result.getMessage();
    }

    @Tool("Remember the current location with a name and optional description")
    public String rememberLocation(
            @P("Name for this location") String name,
            @P("Optional description or tags") String description) {
        log.info("Tool: rememberLocation({}, {})", name, description);
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
    }

    @Tool("Find a previously remembered location by name or description")
    public String findLocation(
            @P("Name or description to search for") String query) {
        log.info("Tool: findLocation({})", query);

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
    }

    @Tool("Save a note for the current player")
    public String rememberNote(
            @P("The player's name") String playerName,
            @P("Note content to save") String content,
            @P("Optional comma-separated tags") String tags) {
        log.info("Tool: rememberNote({}, ...)", playerName);
        playerNoteService.save(playerName, content, tags);
        return "Saved note for " + playerName + ".";
    }

    @Tool("Recall notes for a player, optionally filtering by a query")
    public String recallNotes(
            @P("The player's name") String playerName,
            @P("Optional search query") String query) {
        log.info("Tool: recallNotes({}, {})", playerName, query);
        var notes = (query == null || query.isBlank())
                ? playerNoteService.findByPlayer(playerName)
                : playerNoteService.search(playerName, query);
        if (notes.isEmpty()) {
            return "No notes found for " + playerName + ".";
        }
        return notes.stream()
                .map(n -> "- " + n.getContent())
                .collect(Collectors.joining("\n"));
    }

    @Tool("Cancel the bot's current operation")
    public String cancelCurrentOperation() {
        log.info("Tool: cancelCurrentOperation");
        bot.cancel();
        return "Cancelled current operation.";
    }

    @Tool("Get the bot's current coordinates")
    public String getCurrentPosition() {
        Location pos = bot.getCurrentPosition();
        return "Current position: " + pos;
    }

    @Tool("Get another player's current coordinates by name. Only works if the player is loaded in the world (within render distance).")
    public String getPlayerPosition(
            @P("The player's name to look up") String playerName) {
        log.info("Tool: getPlayerPosition({})", playerName);
        var pos = bot.getPlayerPosition(playerName);
        return pos.map(loc -> playerName + " is at " + loc)
                .orElse("I can't see " + playerName + " right now. They may be out of range or offline.");
    }

    @Tool("Locate a player by name and report their coordinates, distance, and direction. Only works if the player is loaded.")
    public String locatePlayer(@P("Player name") String playerName) {
        log.info("Tool: locatePlayer({})", playerName);
        PlayerInfo player = bot.findPlayer(playerName);
        if (player == null) {
            return "I can't see " + playerName + " right now. They may be out of range or offline.";
        }
        return String.format("Player %s is at %s, %.0f blocks %s",
                player.getName(), player.getLocation(), player.getDistance(), player.getDirection());
    }

    @Tool("List all nearby players within a radius, with coordinates, distance, and direction")
    public String scanForPlayers(@P("Search radius in blocks") int radius) {
        log.info("Tool: scanForPlayers({})", radius);
        List<PlayerInfo> players = bot.getNearbyPlayers(radius);
        if (players.isEmpty()) {
            return "No players found within " + radius + " blocks.";
        }
        return players.stream()
                .map(p -> String.format("Player %s is at %s, %.0f blocks %s",
                        p.getName(), p.getLocation(), p.getDistance(), p.getDirection()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("Scan for nearby mobs or animals of a specific type. Entity type examples: Creeper, Zombie, Pig, Cow, Skeleton, Spider.")
    public String scanForEntities(@P("Entity type, e.g. Creeper, Zombie, Pig, Cow") String entityType,
                                  @P("Search radius in blocks") int radius) {
        log.info("Tool: scanForEntities({}, {})", entityType, radius);
        List<EntityInfo> entities = bot.getNearbyEntities(entityType, radius);
        if (entities.isEmpty()) {
            return "No " + entityType + " found within " + radius + " blocks.";
        }
        return entities.stream()
                .map(e -> String.format("%s at %s, %.0f blocks %s",
                        e.getType(), e.getLocation(), e.getDistance(), e.getDirection()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("Navigate to surface X,Z coordinates. The bot will find any Y level to reach the target.")
    public String navigateToSurface(
            @P("X coordinate") int x,
            @P("Z coordinate") int z) {
        log.info("Tool: navigateToSurface({}, {})", x, z);
        var result = bot.navigateToXZ(x, z);
        if (result.isSuccess()) {
            chatService.send("Navigating to surface coordinates (" + x + ", " + z + ")");
            return "Navigating to surface (" + x + ", " + z + ")";
        }
        return "Cannot navigate: " + result.getMessage();
    }

    @Tool("Go to a specific Y level. Useful for strip mining at a particular depth.")
    public String goToDepth(
            @P("Target Y level") int y) {
        log.info("Tool: goToDepth({})", y);
        var result = bot.navigateToYLevel(y);
        if (result.isSuccess()) {
            chatService.send("Going to Y=" + y);
            return "Going to Y=" + y;
        }
        return "Cannot navigate: " + result.getMessage();
    }

    @Tool("Explore within a radius of a center point. The bot will path to a random point inside the area.")
    public String exploreArea(
            @P("Center X") int x,
            @P("Center Y") int y,
            @P("Center Z") int z,
            @P("Radius in blocks") int radius) {
        log.info("Tool: exploreArea({}, {}, {}, {})", x, y, z, radius);
        var center = new BotOperations.Location(x, y, z);
        var result = bot.exploreNear(center, radius);
        if (result.isSuccess()) {
            chatService.send("Exploring within " + radius + " blocks of (" + x + ", " + y + ", " + z + ")");
            return "Exploring within " + radius + " blocks of (" + x + ", " + y + ", " + z + ")";
        }
        return "Cannot explore: " + result.getMessage();
    }

    @Tool("Flee from specific coordinates to maintain a safe distance. Use when threats are nearby.")
    public String fleeFrom(
            @P("Threat X") int x,
            @P("Threat Y") int y,
            @P("Threat Z") int z,
            @P("Safe distance in blocks") int distance) {
        log.info("Tool: fleeFrom({}, {}, {}, {})", x, y, z, distance);
        var threat = new BotOperations.Location(x, y, z);
        var result = bot.fleeFrom(threat, distance);
        if (result.isSuccess()) {
            chatService.send("Fleeing to maintain " + distance + " blocks from threat");
            return "Fleeing from (" + x + ", " + y + ", " + z + "), maintaining " + distance + " blocks";
        }
        return "Cannot flee: " + result.getMessage();
    }

    @Tool("Navigate to the nearest of multiple remembered locations by name. Provide a comma-separated list of location names.")
    public String navigateToNearestLocation(
            @P("Comma-separated location names") String locationNames) {
        log.info("Tool: navigateToNearestLocation({})", locationNames);
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
    }

    @Tool("Send a message to the player chat. Use this to report progress, ask questions, or confirm actions during multi-step tasks.")
    public String sendMessage(
            @P("Message text to send to the player") String message) {
        log.info("Tool: sendMessage({})", message);
        chatService.send(message);
        return "Sent: " + message;
    }

    @Tool("Enable or disable safe mode. Safe mode prevents block breaking, disables parkour/sprint, enables mob avoidance, and allows door usage.")
    public String setSafetyMode(@P("true to enable safe mode, false for normal") boolean enabled) {
        log.info("Tool: setSafetyMode({})", enabled);
        bot.setSafetyMode(enabled);
        if (enabled) {
            chatService.send("Safe mode enabled. I'll be careful.");
            return "Safe mode enabled";
        } else {
            chatService.send("Safe mode disabled. Normal behavior restored.");
            return "Safe mode disabled";
        }
    }

    @Tool("Report the bot's current health, hunger, armor, and any nearby threats.")
    public String getStatusReport() {
        log.info("Tool: getStatusReport");
        var health = bot.getHealthStatus();
        var threats = bot.getNearbyThreats(32);
        String threatStr = threats.isEmpty()
                ? "none"
                : threats.stream().map(Object::toString).collect(Collectors.joining(", "));
        return "Status: " + health + ". Nearby threats: " + threatStr;
    }

    @Tool("Set pathing behavior mode. 'careful' avoids breaking blocks and uses doors. 'aggressive' allows breaking for speed. 'default' restores normal settings.")
    public String setPathingBehavior(@P("Behavior mode: careful, aggressive, or default") String mode) {
        log.info("Tool: setPathingBehavior({})", mode);
        String normalized = mode.toLowerCase();
        if (!normalized.equals("careful") && !normalized.equals("aggressive") && !normalized.equals("default")) {
            return "Invalid mode: " + mode + ". Use careful, aggressive, or default.";
        }
        bot.setPathingBehavior(normalized);
        chatService.send("Pathing behavior set to " + normalized);
        return "Pathing behavior set to " + normalized;
    }

    @Tool("Add a block type to the avoid-breaking list. The bot will path around these blocks instead of breaking them. Examples: minecraft:glass, minecraft:oak_planks")
    public String avoidBreakingBlock(@P("Block ID to avoid breaking, e.g. minecraft:glass") String blockType) {
        log.info("Tool: avoidBreakingBlock({})", blockType);
        bot.addBlockToAvoid(blockType);
        return "Added " + blockType + " to avoid-breaking list";
    }

    @Tool("Clear all custom block avoidance rules and restore defaults.")
    public String clearBlockAvoidance() {
        log.info("Tool: clearBlockAvoidance");
        bot.clearAvoidedBlocks();
        return "Cleared all block avoidance rules.";
    }
}
