package com.mcagent.core.task.postcondition;

import com.mcagent.core.tools.MinecraftTools;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link MinecraftTools#postConditionFor(String, Object...)} returns the
 * correct {@link PostCondition} variant and values for each registered tool.
 */
class PostConditionRegistryTest {

    @Test
    void mineResource_returnsInventoryIncreased_withBlockTypeAndQuantity() {
        Optional<PostCondition> pc = MinecraftTools.postConditionFor("mineResource", "minecraft:iron_ore", 4, 32);

        assertThat(pc).isPresent();
        assertThat(pc.get()).isInstanceOf(InventoryIncreased.class);
        InventoryIncreased inv = (InventoryIncreased) pc.get();
        assertThat(inv.itemId()).isEqualTo("minecraft:iron_ore");
        assertThat(inv.minIncrease()).isEqualTo(4);
    }

    @Test
    void navigateTo_returnsPositionWithin_withCoordinatesAndRadius3() {
        Optional<PostCondition> pc = MinecraftTools.postConditionFor("navigateTo", 100, 64, 200);

        assertThat(pc).isPresent();
        assertThat(pc.get()).isInstanceOf(PositionWithin.class);
        PositionWithin pos = (PositionWithin) pc.get();
        assertThat(pos.targetX()).isEqualTo(100);
        assertThat(pos.targetY()).isEqualTo(64);
        assertThat(pos.targetZ()).isEqualTo(200);
        assertThat(pos.radius()).isEqualTo(3);
    }

    @Test
    void placeBlockAt_returnsInventoryDecreased_withBlockTypeAndCount1() {
        Optional<PostCondition> pc = MinecraftTools.postConditionFor("placeBlockAt", 10, 64, 20, "minecraft:torch");

        assertThat(pc).isPresent();
        assertThat(pc.get()).isInstanceOf(InventoryDecreased.class);
        InventoryDecreased inv = (InventoryDecreased) pc.get();
        assertThat(inv.itemId()).isEqualTo("minecraft:torch");
        assertThat(inv.minDecrease()).isEqualTo(1);
    }

    @Test
    void buildArea_returnsInventoryDecreased_withBlockTypeAndVolume() {
        // Fix 3: buildArea now calculates actual volume, not a hard-coded 1.
        // (0,64,0) to (4,64,4): |4-0|+1=5, |64-64|+1=1, |4-0|+1=5 → volume = 25.
        Optional<PostCondition> pc = MinecraftTools.postConditionFor(
                "buildArea", 0, 64, 0, 4, 64, 4, "minecraft:oak_planks");

        assertThat(pc).isPresent();
        assertThat(pc.get()).isInstanceOf(InventoryDecreased.class);
        InventoryDecreased inv = (InventoryDecreased) pc.get();
        assertThat(inv.itemId()).isEqualTo("minecraft:oak_planks");
        assertThat(inv.minDecrease()).isEqualTo(25);
    }

    @Test
    void eatFood_returnsNoOp() {
        Optional<PostCondition> pc = MinecraftTools.postConditionFor("eatFood", "minecraft:bread");

        assertThat(pc).isPresent();
        assertThat(pc.get()).isInstanceOf(NoOp.class);
    }

    @Test
    void placeTorchAtFeet_returnsInventoryDecreased_withTorch() {
        Optional<PostCondition> pc = MinecraftTools.postConditionFor("placeTorchAtFeet");

        assertThat(pc).isPresent();
        assertThat(pc.get()).isInstanceOf(InventoryDecreased.class);
        InventoryDecreased inv = (InventoryDecreased) pc.get();
        assertThat(inv.itemId()).isEqualTo("minecraft:torch");
        assertThat(inv.minDecrease()).isEqualTo(1);
    }

    @Test
    void pickupNearbyItems_returnsEmpty_notInRegistry() {
        // pickupNearbyItems is not registered in the POST_CONDITIONS registry.
        // Callers (e.g. JobSupervisor) treat an empty Optional as a NoOp.
        Optional<PostCondition> pc = MinecraftTools.postConditionFor("pickupNearbyItems", 16);

        assertThat(pc).isEmpty();
    }

    @Test
    void unknownTool_returnsEmpty() {
        Optional<PostCondition> pc = MinecraftTools.postConditionFor("followPlayer", "steve");

        assertThat(pc).isEmpty();
    }
}
