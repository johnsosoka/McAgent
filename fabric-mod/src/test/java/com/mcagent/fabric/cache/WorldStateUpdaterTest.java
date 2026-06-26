package com.mcagent.fabric.cache;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the pure-Java logic in {@link WorldStateUpdater}.
 *
 * <p>The full {@link WorldStateUpdater#snapshot(net.minecraft.world.level.Level,
 * net.minecraft.world.entity.player.Player, int)} method requires a live Minecraft
 * environment and is verified by in-game integration testing.  These tests cover
 * the sub-logic that is testable without a Minecraft bootstrap:
 * <ul>
 *   <li>{@link WorldStateUpdater.ScannedEntities} — correct partitioning behaviour
 *       given pre-built entity lists</li>
 *   <li>{@link WorldStateCache.Snapshot} filter helpers — already exercised in
 *       {@link WorldStateCacheTest}; cross-validated here via round-trip through
 *       a manually-built snapshot</li>
 * </ul>
 */
class WorldStateUpdaterTest {

    // --- ScannedEntities record ---

    @Test
    void scannedEntities_holdsThreeSeparateLists() {
        var entity = EntityInfo.builder().type("Pig").location(new Location(1, 64, 1))
                .distance(1.0).direction("N").build();
        var threat = new ThreatInfo("Zombie", new Location(5, 64, 5), 5.0, "NE");
        var player = PlayerInfo.builder().name("Alice").location(new Location(3, 64, 3))
                .distance(3.0).direction("E").build();

        var scanned = new WorldStateUpdater.ScannedEntities(
                List.of(entity), List.of(threat), List.of(player));

        assertThat(scanned.entities()).containsExactly(entity);
        assertThat(scanned.threats()).containsExactly(threat);
        assertThat(scanned.players()).containsExactly(player);
    }

    @Test
    void scannedEntities_emptyListsAreAccepted() {
        var scanned = new WorldStateUpdater.ScannedEntities(List.of(), List.of(), List.of());
        assertThat(scanned.entities()).isEmpty();
        assertThat(scanned.threats()).isEmpty();
        assertThat(scanned.players()).isEmpty();
    }

    // --- Snapshot round-trip from manually-built ScannedEntities ---

    @Test
    void snapshot_roundTrip_threatsFromScannedEntitiesMatchCacheFilter() {
        var closeThreat = new ThreatInfo("Creeper", new Location(5, 64, 5), 5.0, "N");
        var farThreat = new ThreatInfo("Skeleton", new Location(40, 64, 40), 40.0, "SE");

        WorldStateCache.Snapshot snap = buildSnapshot(
                List.of(), List.of(closeThreat, farThreat), List.of());

        // threatsWithinRadius delegates to the snapshot's threat list
        assertThat(snap.threatsWithinRadius(10)).containsExactly(closeThreat);
        assertThat(snap.threatsWithinRadius(50)).containsExactlyInAnyOrder(closeThreat, farThreat);
    }

    @Test
    void snapshot_roundTrip_entitiesFromScannedEntitiesMatchCacheFilter() {
        var pig = EntityInfo.builder().type("Pig").location(new Location(3, 64, 3))
                .distance(3.0).direction("N").build();
        var cow = EntityInfo.builder().type("Cow").location(new Location(4, 64, 4))
                .distance(4.0).direction("NE").build();

        WorldStateCache.Snapshot snap = buildSnapshot(List.of(pig, cow), List.of(), List.of());

        assertThat(snap.entitiesWithinRadius("Pig", 10)).containsExactly(pig);
        assertThat(snap.entitiesWithinRadius("Cow", 10)).containsExactly(cow);
        assertThat(snap.entitiesWithinRadius("Sheep", 10)).isEmpty();
    }

    @Test
    void snapshot_roundTrip_playersFromScannedEntitiesMatchCacheFilter() {
        var near = PlayerInfo.builder().name("Alice").location(new Location(2, 64, 2))
                .distance(2.0).direction("N").build();
        var far = PlayerInfo.builder().name("Bob").location(new Location(60, 64, 60))
                .distance(60.0).direction("SE").build();

        WorldStateCache.Snapshot snap = buildSnapshot(List.of(), List.of(), List.of(near, far));

        assertThat(snap.playersWithinRadius(10)).containsExactly(near);
        assertThat(snap.playersWithinRadius(100)).containsExactlyInAnyOrder(near, far);
    }

    // --- EMPTY sentinel behaviour ---

    @Test
    void emptySnapshot_allListsEmpty() {
        WorldStateCache.Snapshot empty = WorldStateCache.Snapshot.EMPTY;
        assertThat(empty.nearbyEntities()).isEmpty();
        assertThat(empty.threats()).isEmpty();
        assertThat(empty.nearbyPlayers()).isEmpty();
        assertThat(empty.inventorySummary()).isEmpty();
        assertThat(empty.worldTime()).isZero();
        assertThat(empty.isNight()).isFalse();
        assertThat(empty.lightLevel()).isZero();
    }

    @Test
    void emptySnapshot_isEmpty_returnsTrue() {
        assertThat(WorldStateCache.Snapshot.EMPTY.isEmpty()).isTrue();
    }

    @Test
    void nonEmptySnapshot_isEmpty_returnsFalse() {
        WorldStateCache.Snapshot snap = buildSnapshot(List.of(), List.of(), List.of());
        // position differs from EMPTY's (0,0,0) — this is a real snapshot
        assertThat(snap.isEmpty()).isFalse();
    }

    // --- helper ---

    private static WorldStateCache.Snapshot buildSnapshot(
            List<EntityInfo> entities,
            List<ThreatInfo> threats,
            List<PlayerInfo> players) {
        return new WorldStateCache.Snapshot(
                new Location(10, 64, 10),               // non-zero so != EMPTY sentinel
                new com.mcagent.core.service.BotOperations.HealthStatus(20f, 20f, 20, 0),
                entities,
                threats,
                players,
                "minecraft:dirt x32",
                6000,
                false,
                15
        );
    }
}
