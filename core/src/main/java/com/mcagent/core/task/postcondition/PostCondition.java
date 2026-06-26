package com.mcagent.core.task.postcondition;

/**
 * A verifiable expected outcome for a tool invocation.
 *
 * <p>Each variant expresses one kind of world-state assertion that can be checked
 * by comparing a {@link WorldStateSnapshot} taken before the tool call with one
 * taken after. The {@link #describe()} method produces a human-readable string
 * suitable for inclusion in a {@code <framework>} message.</p>
 *
 * <p>Pattern matching over the sealed hierarchy is handled by
 * {@link PostConditionChecker}.</p>
 */
public sealed interface PostCondition
        permits InventoryIncreased, InventoryDecreased, PositionWithin, NoOp {

    /** Short human-readable description, e.g. {@code "inventory[minecraft:iron_ore] increased by >= 4"}. */
    String describe();
}
