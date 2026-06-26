package com.mcagent.core.task.postcondition;

import com.mcagent.core.service.BotOperations;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PostConditionChecker}.
 * Each test covers one variant of the sealed hierarchy under one scenario.
 */
class PostConditionCheckerTest {

    // -------------------------------------------------------------------------
    // InventoryIncreased
    // -------------------------------------------------------------------------

    @Test
    void inventoryIncreased_returnsTrue_whenGainMeetsMinimum() {
        var before = snap(Map.of("minecraft:iron_ore", 0));
        var after  = snap(Map.of("minecraft:iron_ore", 4));

        boolean result = PostConditionChecker.check(new InventoryIncreased("minecraft:iron_ore", 4), before, after);

        assertThat(result).isTrue();
    }

    @Test
    void inventoryIncreased_returnsTrue_whenGainExceedsMinimum() {
        var before = snap(Map.of("minecraft:iron_ore", 2));
        var after  = snap(Map.of("minecraft:iron_ore", 10));

        boolean result = PostConditionChecker.check(new InventoryIncreased("minecraft:iron_ore", 4), before, after);

        assertThat(result).isTrue();
    }

    @Test
    void inventoryIncreased_returnsFalse_whenGainBelowMinimum() {
        var before = snap(Map.of("minecraft:iron_ore", 0));
        var after  = snap(Map.of("minecraft:iron_ore", 3));

        boolean result = PostConditionChecker.check(new InventoryIncreased("minecraft:iron_ore", 4), before, after);

        assertThat(result).isFalse();
    }

    @Test
    void inventoryIncreased_treatsMissingKeyAsZero() {
        var before = snap(Map.of());
        var after  = snap(Map.of("minecraft:coal", 5));

        boolean result = PostConditionChecker.check(new InventoryIncreased("minecraft:coal", 5), before, after);

        assertThat(result).isTrue();
    }

    // -------------------------------------------------------------------------
    // InventoryDecreased
    // -------------------------------------------------------------------------

    @Test
    void inventoryDecreased_returnsTrue_whenDropMeetsMinimum() {
        var before = snap(Map.of("minecraft:torch", 10));
        var after  = snap(Map.of("minecraft:torch", 9));

        boolean result = PostConditionChecker.check(new InventoryDecreased("minecraft:torch", 1), before, after);

        assertThat(result).isTrue();
    }

    @Test
    void inventoryDecreased_returnsTrue_whenDropExceedsMinimum() {
        var before = snap(Map.of("minecraft:torch", 10));
        var after  = snap(Map.of("minecraft:torch", 5));

        boolean result = PostConditionChecker.check(new InventoryDecreased("minecraft:torch", 1), before, after);

        assertThat(result).isTrue();
    }

    @Test
    void inventoryDecreased_returnsFalse_whenCountUnchanged() {
        var before = snap(Map.of("minecraft:torch", 10));
        var after  = snap(Map.of("minecraft:torch", 10));

        boolean result = PostConditionChecker.check(new InventoryDecreased("minecraft:torch", 1), before, after);

        assertThat(result).isFalse();
    }

    @Test
    void inventoryDecreased_returnsFalse_whenCountActuallyIncreased() {
        var before = snap(Map.of("minecraft:oak_planks", 4));
        var after  = snap(Map.of("minecraft:oak_planks", 8));

        boolean result = PostConditionChecker.check(new InventoryDecreased("minecraft:oak_planks", 1), before, after);

        assertThat(result).isFalse();
    }

    // -------------------------------------------------------------------------
    // PositionWithin
    // -------------------------------------------------------------------------

    @Test
    void positionWithin_returnsTrue_whenExactlyAtTarget() {
        var before = snapAt(0, 0, 0);
        var after  = snapAt(100, 64, 200);

        boolean result = PostConditionChecker.check(new PositionWithin(100, 64, 200, 3), before, after);

        assertThat(result).isTrue();
    }

    @Test
    void positionWithin_returnsTrue_whenWithinRadius() {
        var before = snapAt(0, 0, 0);
        var after  = snapAt(102, 64, 200); // 2 blocks off in X

        boolean result = PostConditionChecker.check(new PositionWithin(100, 64, 200, 3), before, after);

        assertThat(result).isTrue();
    }

    @Test
    void positionWithin_returnsFalse_whenOutsideRadius() {
        var before = snapAt(0, 0, 0);
        var after  = snapAt(110, 64, 200); // 10 blocks off

        boolean result = PostConditionChecker.check(new PositionWithin(100, 64, 200, 3), before, after);

        assertThat(result).isFalse();
    }

    @Test
    void positionWithin_ignoresBeforeSnapshot() {
        // Position in 'before' snapshot is irrelevant — only 'after' position is checked.
        var before = snapAt(999, 999, 999);
        var after  = snapAt(100, 64, 200);

        boolean result = PostConditionChecker.check(new PositionWithin(100, 64, 200, 3), before, after);

        assertThat(result).isTrue();
    }

    // -------------------------------------------------------------------------
    // NoOp
    // -------------------------------------------------------------------------

    @Test
    void noOp_alwaysReturnsTrue_regardlessOfSnapshots() {
        var before = WorldStateSnapshot.empty();
        var after  = WorldStateSnapshot.empty();

        boolean result = PostConditionChecker.check(new NoOp(), before, after);

        assertThat(result).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static WorldStateSnapshot snap(Map<String, Integer> counts) {
        return new WorldStateSnapshot(new BotOperations.Location(0, 0, 0), counts);
    }

    private static WorldStateSnapshot snapAt(int x, int y, int z) {
        return new WorldStateSnapshot(new BotOperations.Location(x, y, z), Map.of());
    }
}
