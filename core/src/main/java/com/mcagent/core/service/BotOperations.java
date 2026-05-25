package com.mcagent.core.service;

import com.mcagent.core.model.PathResult;

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
