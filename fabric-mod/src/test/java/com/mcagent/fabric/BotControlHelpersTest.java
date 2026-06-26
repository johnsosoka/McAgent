package com.mcagent.fabric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BotControlHelpers}.
 * No Minecraft bootstrap required — all logic is pure Java.
 */
class BotControlHelpersTest {

    // ------------------------------------------------------------------
    // chooseBestFoodSlot
    // ------------------------------------------------------------------

    @Test
    void chooseBestFoodSlot_picksMostPreferredItem() {
        List<BotControlHelpers.InventorySlot> slots = List.of(
                new BotControlHelpers.InventorySlot(3, "minecraft:apple", 1),
                new BotControlHelpers.InventorySlot(7, "minecraft:cooked_beef", 2),
                new BotControlHelpers.InventorySlot(9, "minecraft:bread", 3)
        );

        int result = BotControlHelpers.chooseBestFoodSlot(slots, BotControlHelpers.FOOD_PRIORITY);

        // cooked_beef is higher priority than apple or bread
        assertThat(result).isEqualTo(7);
    }

    @Test
    void chooseBestFoodSlot_returnsNegativeOne_whenNoFoodPresent() {
        List<BotControlHelpers.InventorySlot> slots = List.of(
                new BotControlHelpers.InventorySlot(0, "minecraft:cobblestone", 64),
                new BotControlHelpers.InventorySlot(1, "minecraft:torch", 8)
        );

        int result = BotControlHelpers.chooseBestFoodSlot(slots, BotControlHelpers.FOOD_PRIORITY);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void chooseBestFoodSlot_returnsNegativeOne_whenSlotsEmpty() {
        int result = BotControlHelpers.chooseBestFoodSlot(List.of(), BotControlHelpers.FOOD_PRIORITY);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void chooseBestFoodSlot_skipsZeroCountSlots() {
        List<BotControlHelpers.InventorySlot> slots = List.of(
                new BotControlHelpers.InventorySlot(5, "minecraft:golden_carrot", 0),
                new BotControlHelpers.InventorySlot(6, "minecraft:bread", 2)
        );

        int result = BotControlHelpers.chooseBestFoodSlot(slots, BotControlHelpers.FOOD_PRIORITY);

        // golden_carrot is count=0 so should be skipped; bread at slot 6 is next
        assertThat(result).isEqualTo(6);
    }

    @Test
    void chooseBestFoodSlot_isCaseInsensitive() {
        List<BotControlHelpers.InventorySlot> slots = List.of(
                new BotControlHelpers.InventorySlot(2, "MINECRAFT:BREAD", 1)
        );

        int result = BotControlHelpers.chooseBestFoodSlot(slots, BotControlHelpers.FOOD_PRIORITY);

        assertThat(result).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // findItemSlot
    // ------------------------------------------------------------------

    @Test
    void findItemSlot_findsExactMatch() {
        List<BotControlHelpers.InventorySlot> slots = List.of(
                new BotControlHelpers.InventorySlot(0, "minecraft:cobblestone", 64),
                new BotControlHelpers.InventorySlot(4, "minecraft:torch", 10)
        );

        int result = BotControlHelpers.findItemSlot(slots, "minecraft:torch");

        assertThat(result).isEqualTo(4);
    }

    @Test
    void findItemSlot_qualifiesUnprefixedId() {
        List<BotControlHelpers.InventorySlot> slots = List.of(
                new BotControlHelpers.InventorySlot(1, "minecraft:bread", 3)
        );

        // Pass unqualified "bread" — helper should qualify to "minecraft:bread"
        int result = BotControlHelpers.findItemSlot(slots, "bread");

        assertThat(result).isEqualTo(1);
    }

    @Test
    void findItemSlot_returnsNegativeOne_whenNotFound() {
        List<BotControlHelpers.InventorySlot> slots = List.of(
                new BotControlHelpers.InventorySlot(0, "minecraft:stone", 32)
        );

        int result = BotControlHelpers.findItemSlot(slots, "minecraft:diamond");

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void findItemSlot_skipsZeroCountSlots() {
        List<BotControlHelpers.InventorySlot> slots = List.of(
                new BotControlHelpers.InventorySlot(2, "minecraft:apple", 0)
        );

        int result = BotControlHelpers.findItemSlot(slots, "minecraft:apple");

        assertThat(result).isEqualTo(-1);
    }

    // ------------------------------------------------------------------
    // isNightTick
    // ------------------------------------------------------------------

    @Test
    void isNightTick_falseAtDawn() {
        assertThat(BotControlHelpers.isNightTick(0)).isFalse();
    }

    @Test
    void isNightTick_falseAtNoon() {
        assertThat(BotControlHelpers.isNightTick(6000)).isFalse();
    }

    @Test
    void isNightTick_falseAtDusk() {
        assertThat(BotControlHelpers.isNightTick(12999)).isFalse();
    }

    @Test
    void isNightTick_trueAtNightStart() {
        assertThat(BotControlHelpers.isNightTick(13000)).isTrue();
    }

    @Test
    void isNightTick_trueAtMidnight() {
        assertThat(BotControlHelpers.isNightTick(18000)).isTrue();
    }

    @Test
    void isNightTick_trueAtNightEnd() {
        assertThat(BotControlHelpers.isNightTick(23000)).isTrue();
    }

    @Test
    void isNightTick_falseJustAfterNightEnd() {
        assertThat(BotControlHelpers.isNightTick(23001)).isFalse();
    }

    @Test
    void isNightTick_handlesMultiDayCycle() {
        // tick 24000 is the same as 0 (dawn)
        assertThat(BotControlHelpers.isNightTick(24000)).isFalse();
        // tick 37000 = 13000 mod 24000 — start of night
        assertThat(BotControlHelpers.isNightTick(37000)).isTrue();
    }

    // ------------------------------------------------------------------
    // qualify
    // ------------------------------------------------------------------

    @Test
    void qualify_addsNamespace_whenMissing() {
        assertThat(BotControlHelpers.qualify("bread")).isEqualTo("minecraft:bread");
    }

    @Test
    void qualify_preservesExistingNamespace() {
        assertThat(BotControlHelpers.qualify("minecraft:golden_carrot")).isEqualTo("minecraft:golden_carrot");
    }

    @Test
    void qualify_lowercasesInput() {
        assertThat(BotControlHelpers.qualify("BREAD")).isEqualTo("minecraft:bread");
    }

    @Test
    void qualify_handlesNullGracefully() {
        assertThat(BotControlHelpers.qualify(null)).isEqualTo("");
    }
}
