package com.mcagent.core.service;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PathResult;
import com.mcagent.core.model.PlayerInfo;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Abstraction over Minecraft bot operations.
 * Implemented in the mod layer using Baritone.
 */
public interface BotOperations {

    PathResult navigateTo(int x, int y, int z);

    PathResult navigateTo(String locationName);

    PathResult followPlayer(String playerName);

    PathResult mine(String blockType, int maxBlocks, Integer radius);

    void cancel();

    void pause();

    void resume();

    Location getCurrentPosition();

    /**
     * Look up a player's current coordinates by name in the loaded world.
     * Returns empty if the player is not visible (out of render distance or offline).
     */
    Optional<Location> getPlayerPosition(String playerName);

    /**
     * Locate a player by name in the loaded world and return their position,
     * distance from the bot, and relative direction.
     * Returns null if the player is not visible.
     */
    PlayerInfo findPlayer(String playerName);

    /**
     * Scan for all loaded players within a given radius and return their info.
     */
    List<PlayerInfo> getNearbyPlayers(int radius);

    /**
     * Scan for entities of a specific type within a given radius.
     * entityType should match Minecraft entity class names (e.g. "Creeper", "Zombie", "Pig").
     */
    List<EntityInfo> getNearbyEntities(String entityType, int radius);

    /**
     * Navigate to surface X,Z coordinates (any Y level).
     * Uses Baritone's GoalXZ.
     */
    PathResult navigateToXZ(int x, int z);

    /**
     * Navigate to a specific Y level (any X,Z).
     * Useful for strip mining. Uses Baritone's GoalYLevel.
     */
    PathResult navigateToYLevel(int y);

    /**
     * Explore within a given radius of a center point.
     * Uses Baritone's GoalNear or GoalXZ with random offset.
     */
    PathResult exploreNear(Location center, int radius);

    /**
     * Retreat from a specific coordinate to maintain a safe distance.
     * Uses Baritone's GoalInverted.
     */
    PathResult fleeFrom(Location threat, int safeDistance);

    /**
     * Navigate to the nearest of multiple candidate locations.
     * Uses Baritone's GoalComposite.
     */
    PathResult navigateToNearest(List<Location> candidates);

    /**
     * Register a callback that receives human-readable progress/status messages
     * from the bot (e.g. "Arrived at destination", "Mining complete").
     */
    void setProgressCallback(Consumer<String> callback);

    record Location(int x, int y, int z) {
        public double distanceTo(Location other) {
            return Math.sqrt(
                    Math.pow(x - other.x, 2) +
                            Math.pow(y - other.y, 2) +
                            Math.pow(z - other.z, 2)
            );
        }

        @Override
        public String toString() {
            return String.format("(%d, %d, %d)", x, y, z);
        }
    }
}
