package com.mcagent.core.task.postcondition;

/**
 * Post-condition: the count of {@code itemId} in inventory must have dropped by at least
 * {@code minDecrease} between the before and after snapshots.
 */
public record InventoryDecreased(String itemId, int minDecrease) implements PostCondition {

    @Override
    public String describe() {
        return "inventory[" + itemId + "] decreased by >= " + minDecrease;
    }
}
