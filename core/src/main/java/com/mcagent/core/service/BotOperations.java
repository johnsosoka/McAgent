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
     * Enable or disable safety mode. When enabled, the bot avoids mobs,
     * disables parkour/sprint, avoids breaking blocks, and uses doors.
     */
    void setSafetyMode(boolean enabled);

    /**
     * Get the bot's current health and hunger status.
     */
    HealthStatus getHealthStatus();

    /**
     * Scan for hostile mobs within a radius and report them as threats
     * with distance and direction.
     */
    List<ThreatInfo> getNearbyThreats(int radius);

    /**
     * Set pathing behavior mode: "careful", "aggressive", or "default".
     * careful: no breaking, no parkour/sprint, uses doors
     * aggressive: allows breaking, parkour, sprint
     * default: restores Baritone defaults
     */
    void setPathingBehavior(String mode);

    /**
     * Add a block type to the avoid-breaking list.
     * blockType should be a Minecraft block ID, e.g. "minecraft:glass".
     */
    void addBlockToAvoid(String blockType);

    /**
     * Clear all custom block avoidance rules and restore defaults.
     */
    void clearAvoidedBlocks();

    /**
     * Register a callback that receives human-readable progress/status messages
     * from the bot (e.g. "Arrived at destination", "Mining complete").
     */
    void setProgressCallback(Consumer<String> callback);

    /**
     * Health and hunger status record.
     */
    record HealthStatus(float health, float maxHealth, int foodLevel, int armorLevel) {
        public boolean isHealthy() {
            return health > maxHealth * 0.5f && foodLevel > 10;
        }

        @Override
        public String toString() {
            return String.format("Health: %.0f/%.0f, Food: %d/20, Armor: %d",
                    health, maxHealth, foodLevel, armorLevel);
        }
    }

    /**
     * Information about a nearby threat (hostile mob).
     */
    record ThreatInfo(String type, Location location, double distance, String direction) {
        @Override
        public String toString() {
            return String.format("%s at %s, %.0f blocks %s", type, location, distance, direction);
        }
    }

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
