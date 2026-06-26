package com.mcagent.fabric.cache;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations.HealthStatus;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.BotOperations.ThreatInfo;

import java.util.List;

/**
 * Thread-safe read-only world state cache, refreshed on the Minecraft client tick.
 *
 * <p>Dispatcher threads and the autonomous observer read from this cache without
 * blocking on the Minecraft client thread. The underlying {@link Snapshot} reference
 * is {@code volatile}, so a writer's update is immediately visible to all readers
 * without locking — Java's memory model guarantees atomic reference writes.</p>
 *
 * <p>The cache is seeded with {@link Snapshot#EMPTY} at construction time. Callers
 * must treat an {@link Snapshot#EMPTY} snapshot as a signal that the cache has not
 * yet been populated (early bootstrap) and fall back to a live query if needed.</p>
 */
public final class WorldStateCache {

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * Return the most recent snapshot. Never null; may be {@link Snapshot#EMPTY}
     * during early bootstrap before the first tick update fires.
     */
    public Snapshot get() {
        return snapshot;
    }

    /**
     * Replace the current snapshot. Called exclusively on the Minecraft client thread
     * by {@link WorldStateUpdater}; safe to read from any thread.
     */
    public void update(Snapshot newSnapshot) {
        if (newSnapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        this.snapshot = newSnapshot;
    }

    /**
     * Immutable point-in-time snapshot of the bot's world state.
     *
     * <p>All fields use only {@code core/} types so the snapshot can be consumed
     * outside of {@code fabric-mod} without pulling in Minecraft classes.</p>
     */
    public record Snapshot(
            Location currentPosition,
            HealthStatus health,
            List<EntityInfo> nearbyEntities,
            List<ThreatInfo> threats,
            List<PlayerInfo> nearbyPlayers,
            String inventorySummary,
            int worldTime,
            boolean isNight,
            int lightLevel
    ) {

        /**
         * Sentinel value returned before the first client-tick update.
         * Callers that need live data should fall back to a direct query
         * when {@code cache.get() == Snapshot.EMPTY}.
         */
        public static final Snapshot EMPTY = new Snapshot(
                new Location(0, 0, 0),
                new HealthStatus(0, 20, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                "",
                0,
                false,
                0
        );

        /**
         * Return {@code true} if this snapshot is the sentinel empty value.
         */
        public boolean isEmpty() {
            return this == EMPTY;
        }

        /**
         * Filter the cached threat list to those within {@code radius} blocks of the
         * current position. Avoids a live client-thread round-trip for radius queries.
         */
        public List<ThreatInfo> threatsWithinRadius(int radius) {
            return threats.stream()
                    .filter(t -> t.distance() <= radius)
                    .toList();
        }

        /**
         * Filter the cached entity list to those within {@code radius} blocks and
         * matching {@code entityType}.
         */
        public List<EntityInfo> entitiesWithinRadius(String entityType, int radius) {
            return nearbyEntities.stream()
                    .filter(e -> entityType.equalsIgnoreCase(e.getType()) && e.getDistance() <= radius)
                    .toList();
        }

        /**
         * Filter the cached player list to those within {@code radius} blocks.
         */
        public List<PlayerInfo> playersWithinRadius(int radius) {
            return nearbyPlayers.stream()
                    .filter(p -> p.getDistance() <= radius)
                    .toList();
        }
    }
}
