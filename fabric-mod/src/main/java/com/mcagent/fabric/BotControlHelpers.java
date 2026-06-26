package com.mcagent.fabric;

import java.util.List;

/**
 * Pure-Java helpers extracted from {@link FabricBaritoneBridge} so that
 * business logic (food ranking, slot selection) can be unit-tested without
 * a Minecraft bootstrap.
 *
 * <p>All methods are stateless and package-visible.</p>
 */
final class BotControlHelpers {

    /**
     * Preferred food items ordered from best to worst.
     * Used when {@code eat(null)} is called to auto-select the highest-value food.
     */
    static final List<String> FOOD_PRIORITY = List.of(
            "minecraft:golden_carrot",
            "minecraft:cooked_beef",
            "minecraft:cooked_porkchop",
            "minecraft:bread",
            "minecraft:apple",
            "minecraft:cooked_chicken"
    );

    /** Night begins at tick 13000 and ends at tick 23000 in the day cycle. */
    static final int NIGHT_START = 13000;
    static final int NIGHT_END = 23000;

    private BotControlHelpers() {
        // utility class
    }

    /**
     * Given the current inventory as a list of (itemId, count) pairs and a
     * priority-ordered food list, return the inventory index of the best food found,
     * or {@code -1} if no food is present.
     *
     * <p>This overload is used when the caller wants auto-selection.</p>
     *
     * @param slots    inventory slots as (itemId, slotIndex) pairs
     * @param priority food item IDs in descending preference order
     * @return the slot index of the best food, or -1 if none found
     */
    static int chooseBestFoodSlot(List<InventorySlot> slots, List<String> priority) {
        for (String foodId : priority) {
            for (InventorySlot slot : slots) {
                if (foodId.equalsIgnoreCase(slot.itemId()) && slot.count() > 0) {
                    return slot.index();
                }
            }
        }
        return -1;
    }

    /**
     * Given the current inventory as a list of (itemId, slotIndex) pairs,
     * return the first slot index that matches the requested itemId exactly
     * (case-insensitive), or {@code -1} if not found.
     *
     * @param slots  inventory slots
     * @param itemId target item ID (fully-qualified, e.g. "minecraft:bread")
     * @return the matching slot index, or -1 if not found
     */
    static int findItemSlot(List<InventorySlot> slots, String itemId) {
        String target = qualify(itemId);
        for (InventorySlot slot : slots) {
            if (target.equalsIgnoreCase(slot.itemId()) && slot.count() > 0) {
                return slot.index();
            }
        }
        return -1;
    }

    /**
     * Return {@code true} if the given day-cycle tick value falls within the
     * night range (13000..23000 inclusive).
     *
     * @param worldTimeTick value in the range 0..23999
     */
    static boolean isNightTick(long worldTimeTick) {
        long tick = worldTimeTick % 24000;
        return tick >= NIGHT_START && tick <= NIGHT_END;
    }

    /**
     * Qualify a Minecraft item ID so it always has a namespace prefix.
     * "bread" becomes "minecraft:bread"; "minecraft:bread" is returned unchanged.
     */
    static String qualify(String itemId) {
        if (itemId == null) {
            return "";
        }
        return itemId.contains(":") ? itemId.toLowerCase() : "minecraft:" + itemId.toLowerCase();
    }

    /**
     * Lightweight record representing a single inventory slot's item identity.
     *
     * @param index  slot index within the player inventory
     * @param itemId fully-qualified Minecraft item ID (e.g. "minecraft:bread")
     * @param count  stack size
     */
    record InventorySlot(int index, String itemId, int count) {}
}
