package com.mcagent.core.service;

import com.mcagent.core.service.ops.BotControlOperations;
import com.mcagent.core.service.ops.BuildingOperations;
import com.mcagent.core.service.ops.InventoryOperations;
import com.mcagent.core.service.ops.NavigationOperations;
import com.mcagent.core.service.ops.ObservationOperations;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural verification that {@link BotOperations} is a proper composite
 * over all five role interfaces introduced in Sprint 12 C2.
 *
 * <p>These tests use reflection so they catch refactoring accidents
 * (accidentally removing an {@code extends} clause) at compile- and test-time
 * rather than only at runtime in-game.</p>
 */
class BotOperationsSplitTest {

    @Test
    void botOperationsExtendsAllFiveRoleInterfaces() {
        Set<Class<?>> superInterfaces = Set.of(BotOperations.class.getInterfaces());

        assertThat(superInterfaces)
                .as("BotOperations must extend NavigationOperations")
                .contains(NavigationOperations.class);
        assertThat(superInterfaces)
                .as("BotOperations must extend ObservationOperations")
                .contains(ObservationOperations.class);
        assertThat(superInterfaces)
                .as("BotOperations must extend InventoryOperations")
                .contains(InventoryOperations.class);
        assertThat(superInterfaces)
                .as("BotOperations must extend BuildingOperations")
                .contains(BuildingOperations.class);
        assertThat(superInterfaces)
                .as("BotOperations must extend BotControlOperations")
                .contains(BotControlOperations.class);
    }

    @Test
    void botOperationsExtendsExactlyFiveRoleInterfaces() {
        // Guard against silent additions — keeps the composite intentional.
        Class<?>[] interfaces = BotOperations.class.getInterfaces();
        assertThat(interfaces)
                .as("BotOperations should extend exactly the five role interfaces")
                .hasSize(5);
    }
}
