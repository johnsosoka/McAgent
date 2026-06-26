package com.mcagent.core.task.postcondition;

/**
 * Evaluates a {@link PostCondition} against two world-state snapshots.
 *
 * <p>Pattern-matches over the sealed {@link PostCondition} hierarchy.  All cases are
 * exhaustive — the compiler enforces that new variants require a new branch here.</p>
 */
public final class PostConditionChecker {

    private PostConditionChecker() {
        // utility class
    }

    /**
     * Return {@code true} if the transition from {@code before} to {@code after}
     * satisfies {@code pc}.
     *
     * @param pc     the post-condition to evaluate
     * @param before world state captured immediately before the tool executed
     * @param after  world state captured immediately after the tool executed
     */
    public static boolean check(PostCondition pc, WorldStateSnapshot before, WorldStateSnapshot after) {
        return switch (pc) {
            case InventoryIncreased(var id, var n) ->
                after.inventoryCounts().getOrDefault(id, 0)
                        - before.inventoryCounts().getOrDefault(id, 0) >= n;

            case InventoryDecreased(var id, var n) ->
                before.inventoryCounts().getOrDefault(id, 0)
                        - after.inventoryCounts().getOrDefault(id, 0) >= n;

            case PositionWithin(var x, var y, var z, var r) -> {
                var p = after.position();
                double dx = p.x() - x;
                double dy = p.y() - y;
                double dz = p.z() - z;
                yield Math.sqrt(dx * dx + dy * dy + dz * dz) <= r;
            }

            case NoOp() -> true;
        };
    }
}
