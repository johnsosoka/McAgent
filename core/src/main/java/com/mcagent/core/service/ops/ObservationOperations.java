package com.mcagent.core.service.ops;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations.HealthStatus;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.BotOperations.ThreatInfo;

import java.util.List;
import java.util.Optional;

/**
 * Read-only world-state observation operations.
 * Consumers that only need to query the world (e.g. {@code AutonomousObserver})
 * depend on this interface rather than the full {@code BotOperations}.
 */
public interface ObservationOperations {

    Location getCurrentPosition();

    /**
     * Look up a player's current coordinates by name.
     * Returns empty if the player is not visible (out of render distance or offline).
     */
    Optional<Location> getPlayerPosition(String playerName);

    /**
     * Locate a player by name and return their position, distance, and relative direction.
     * Returns null if the player is not visible.
     */
    PlayerInfo findPlayer(String playerName);

    /**
     * Scan for all loaded players within a given radius.
     */
    List<PlayerInfo> getNearbyPlayers(int radius);

    /**
     * Scan for entities of a specific type within a given radius.
     * entityType should match Minecraft entity class names (e.g. "Creeper", "Zombie", "Pig").
     */
    List<EntityInfo> getNearbyEntities(String entityType, int radius);

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
     * Return the current world day-cycle position in ticks (0..23999).
     * 0 = dawn, 6000 = noon, 12000 = dusk, 18000 = midnight.
     */
    int getWorldTime();

    /**
     * Convenience check — true when world time is in the night range (~13000..23000).
     */
    boolean isNight();

    /**
     * Return the total light level (sky + block) at the bot's feet position (0–15).
     * Values below 8 allow hostile mob spawning.
     */
    int getLightLevel();
}
