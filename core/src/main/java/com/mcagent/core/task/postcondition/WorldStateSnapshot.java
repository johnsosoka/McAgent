package com.mcagent.core.task.postcondition;

import com.mcagent.core.service.BotOperations;

import java.util.Map;

/**
 * Immutable point-in-time snapshot of the world state attributes relevant to
 * post-condition checking: bot position and a sparse inventory count map.
 *
 * <p>Only the item IDs relevant to the expected post-condition need to be
 * populated. Missing keys are treated as count zero by {@link PostConditionChecker}.</p>
 */
public record WorldStateSnapshot(
        BotOperations.Location position,
        Map<String, Integer> inventoryCounts
) {

    /**
     * Returns a snapshot with zero inventory counts and a zeroed position.
     * Useful as a neutral "before" in tests.
     */
    public static WorldStateSnapshot empty() {
        return new WorldStateSnapshot(new BotOperations.Location(0, 0, 0), Map.of());
    }
}
