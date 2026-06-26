package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A {@link WaitCondition} that is satisfied when the bot's inventory contains
 * at least {@code count} of the given item.
 *
 * @param itemId Minecraft item ID, e.g. {@code "minecraft:iron_ore"}.
 * @param count  Minimum quantity required.
 */
public record InventoryHasCondition(
        @JsonProperty("itemId") String itemId,
        @JsonProperty("count") int count
) implements WaitCondition {

    @JsonCreator
    public InventoryHasCondition {
        // compact canonical constructor — annotations drive Jackson deserialization
    }

    @Override
    public String describe() {
        return "inventory has >= " + count + " " + itemId;
    }
}
