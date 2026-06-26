package com.mcagent.fabric;

import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ops.BotControlOperations;
import com.mcagent.core.service.ops.BuildingOperations;
import com.mcagent.core.service.ops.InventoryOperations;
import com.mcagent.core.service.ops.NavigationOperations;
import com.mcagent.core.service.ops.ObservationOperations;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link FabricBaritoneBridge} implements all five role interfaces
 * and the composite {@link BotOperations} marker — using reflection so that
 * accidentally removing an {@code implements} clause is caught at test time.
 */
class FabricBaritoneBridgeSplitTest {

    @Test
    void bridgeImplementsNavigationOperations() {
        assertThat(NavigationOperations.class.isAssignableFrom(FabricBaritoneBridge.class))
                .as("FabricBaritoneBridge must implement NavigationOperations")
                .isTrue();
    }

    @Test
    void bridgeImplementsObservationOperations() {
        assertThat(ObservationOperations.class.isAssignableFrom(FabricBaritoneBridge.class))
                .as("FabricBaritoneBridge must implement ObservationOperations")
                .isTrue();
    }

    @Test
    void bridgeImplementsInventoryOperations() {
        assertThat(InventoryOperations.class.isAssignableFrom(FabricBaritoneBridge.class))
                .as("FabricBaritoneBridge must implement InventoryOperations")
                .isTrue();
    }

    @Test
    void bridgeImplementsBuildingOperations() {
        assertThat(BuildingOperations.class.isAssignableFrom(FabricBaritoneBridge.class))
                .as("FabricBaritoneBridge must implement BuildingOperations")
                .isTrue();
    }

    @Test
    void bridgeImplementsBotControlOperations() {
        assertThat(BotControlOperations.class.isAssignableFrom(FabricBaritoneBridge.class))
                .as("FabricBaritoneBridge must implement BotControlOperations")
                .isTrue();
    }

    @Test
    void bridgeImplementsBotOperationsMarker() {
        assertThat(BotOperations.class.isAssignableFrom(FabricBaritoneBridge.class))
                .as("FabricBaritoneBridge must implement the BotOperations composite marker")
                .isTrue();
    }
}
