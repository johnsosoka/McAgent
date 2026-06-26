package com.mcagent.core.service.ops;

import com.mcagent.core.model.PathResult;
import com.mcagent.core.service.BotOperations.Location;

import java.util.List;

/**
 * Navigation and pathfinding operations.
 * All movement-initiating commands are grouped here: explicit coordinate goals,
 * follow-player, exploration, fleeing, and mining (which is a navigation-style goal).
 */
public interface NavigationOperations {

    PathResult navigateTo(int x, int y, int z);

    PathResult navigateTo(String locationName);

    PathResult followPlayer(String playerName);

    /**
     * Mine a block type using Baritone's mine process.
     * Grouped with navigation because mining is expressed as a Baritone goal,
     * not an inventory or observation concern.
     */
    PathResult mine(String blockType, int maxBlocks, Integer radius);

    /**
     * Navigate to surface X,Z coordinates (any Y level).
     * Uses Baritone's GoalXZ.
     */
    PathResult navigateToXZ(int x, int z);

    /**
     * Navigate to a specific Y level (any X,Z).
     * Uses Baritone's GoalYLevel.
     */
    PathResult navigateToYLevel(int y);

    /**
     * Explore within a given radius of a center point.
     */
    PathResult exploreNear(Location center, int radius);

    /**
     * Retreat from a specific coordinate to maintain a safe distance.
     */
    PathResult fleeFrom(Location threat, int safeDistance);

    /**
     * Navigate to the nearest of multiple candidate locations.
     * Uses Baritone's GoalComposite.
     */
    PathResult navigateToNearest(List<Location> candidates);
}
