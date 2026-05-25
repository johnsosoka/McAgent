package com.mcagent.core.tools;

import com.mcagent.core.memory.LocationMemoryEntry;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.LocationType;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
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
        return result.isSuccess()
                ? "Following player " + playerName
                : "Cannot follow: " + result.getMessage();
    }

    @Tool("Navigate to specific X Y Z coordinates")
    public String navigateTo(
            @P("X coordinate") int x,
            @P("Y coordinate") int y,
            @P("Z coordinate") int z) {
        log.info("Tool: navigateTo({}, {}, {})", x, y, z);
        var result = bot.navigateTo(x, y, z);
        return result.isSuccess()
                ? "Navigating to (" + x + ", " + y + ", " + z + ")"
                : "Cannot navigate: " + result.getMessage();
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

    @Tool("Send a message to the player chat. Use this to report progress, ask questions, or confirm actions during multi-step tasks.")
    public String sendMessage(
            @P("Message text to send to the player") String message) {
        log.info("Tool: sendMessage({})", message);
        chatService.send(message);
        return "Sent: " + message;
    }
}
