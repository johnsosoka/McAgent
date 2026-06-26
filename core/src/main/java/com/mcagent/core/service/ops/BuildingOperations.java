package com.mcagent.core.service.ops;

import com.mcagent.core.model.PathResult;

/**
 * Block placement and structure-building operations.
 */
public interface BuildingOperations {

    /**
     * Build a filled rectangular area with a specific block type.
     * The bot must have sufficient blocks in inventory.
     * allowPlace is automatically enabled during the build.
     */
    PathResult buildPlatform(int x1, int y1, int z1, int x2, int y2, int z2, String blockType);

    /**
     * Place a single block at the specified coordinates.
     * The bot must have the block in its inventory.
     * allowPlace is automatically enabled.
     */
    PathResult placeBlock(int x, int y, int z, String blockType);
}
