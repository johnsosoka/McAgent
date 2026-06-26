package com.mcagent.core.service;

import com.mcagent.core.service.ops.BotControlOperations;
import com.mcagent.core.service.ops.BuildingOperations;
import com.mcagent.core.service.ops.InventoryOperations;
import com.mcagent.core.service.ops.NavigationOperations;
import com.mcagent.core.service.ops.ObservationOperations;

/**
 * Composite marker interface over all bot operation roles.
 *
 * <p>Split into focused role interfaces under {@code com.mcagent.core.service.ops}:
 * <ul>
 *   <li>{@link NavigationOperations} — movement, pathfinding, mining goals</li>
 *   <li>{@link ObservationOperations} — read-only world-state queries</li>
 *   <li>{@link InventoryOperations} — inventory queries</li>
 *   <li>{@link BuildingOperations} — block placement and structure building</li>
 *   <li>{@link BotControlOperations} — Baritone settings and lifecycle control</li>
 * </ul>
 *
 * <p>Existing consumers that depend on {@code BotOperations} continue to compile
 * and run without modification. New consumers (e.g. {@code AutonomousObserver},
 * future planner) should declare only the narrower role interface they require.
 *
 * <p>The inner records ({@link Location}, {@link HealthStatus}, {@link ThreatInfo})
 * are kept here because they are shared across multiple role interfaces. Moving them
 * to individual role interfaces would create circular import chains; keeping them on
 * the marker hub is the minimal-touch approach.
 */
public interface BotOperations extends
        NavigationOperations,
        ObservationOperations,
        InventoryOperations,
        BuildingOperations,
        BotControlOperations {

    /**
     * Health and hunger status record.
     */
    record HealthStatus(float health, float maxHealth, int foodLevel, int armorLevel) {
        public boolean isHealthy() {
            return health > maxHealth * 0.5f && foodLevel > 10;
        }

        @Override
        public String toString() {
            return String.format("Health: %.0f/%.0f, Food: %d/20, Armor: %d",
                    health, maxHealth, foodLevel, armorLevel);
        }
    }

    /**
     * Information about a nearby threat (hostile mob).
     */
    record ThreatInfo(String type, Location location, double distance, String direction) {
        @Override
        public String toString() {
            return String.format("%s at %s, %.0f blocks %s", type, location, distance, direction);
        }
    }

    record Location(int x, int y, int z) {
        public double distanceTo(Location other) {
            return Math.sqrt(
                    Math.pow(x - other.x, 2) +
                            Math.pow(y - other.y, 2) +
                            Math.pow(z - other.z, 2)
            );
        }

        @Override
        public String toString() {
            return String.format("(%d, %d, %d)", x, y, z);
        }
    }
}
