package com.mcagent.core.service.ops;

/**
 * Inventory query operations.
 * Read-only view of the bot's held items.
 */
public interface InventoryOperations {

    /**
     * Check if the bot has at least {@code count} of the given item in inventory.
     * itemId should be a Minecraft item ID, e.g. "minecraft:cobblestone".
     */
    boolean hasItem(String itemId, int count);

    /**
     * Count the total number of the given item in the bot's inventory.
     * itemId should be a Minecraft item ID, e.g. "minecraft:cobblestone".
     */
    int countItem(String itemId);

    /**
     * Get a human-readable summary of the bot's inventory.
     * Lists top items or categories, truncated to fit a chat message.
     */
    String getInventorySummary();

    /**
     * Walk to and collect any item entities within the given block radius.
     * Vanilla pickup happens automatically when the bot's path crosses an item.
     * Returns a short summary of how many items were targeted.
     *
     * @param radius search radius in blocks
     */
    String pickupNearbyItems(int radius);
}
