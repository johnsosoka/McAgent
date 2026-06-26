package com.mcagent.core.task.postcondition;

/**
 * Post-condition: the count of {@code itemId} in inventory must have grown by at least
 * {@code minIncrease} between the before and after snapshots.
 */
public record InventoryIncreased(String itemId, int minIncrease) implements PostCondition {

    @Override
    public String describe() {
        return "inventory[" + itemId + "] increased by >= " + minIncrease;
    }
}
