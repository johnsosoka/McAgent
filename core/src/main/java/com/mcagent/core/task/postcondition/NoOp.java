package com.mcagent.core.task.postcondition;

/**
 * Sentinel post-condition meaning "no verifiable world-state change is expected."
 *
 * <p>Used for tools whose effects are immediate or not quantifiable via inventory /
 * position deltas (e.g. {@code eatFood}, {@code pickupNearbyItems}).</p>
 *
 * <p>{@link PostConditionChecker} always returns {@code true} for this variant,
 * and the framework-message emitter skips it to avoid noise.</p>
 */
public record NoOp() implements PostCondition {

    @Override
    public String describe() {
        return "(no post-condition)";
    }
}
