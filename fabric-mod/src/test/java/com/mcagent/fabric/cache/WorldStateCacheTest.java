package com.mcagent.fabric.cache;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations.HealthStatus;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldStateCacheTest {

    // --- Snapshot.EMPTY sentinel ---

    @Test
    void emptySnapshotIsDefaultState() {
        var cache = new WorldStateCache();
        assertThat(cache.get()).isSameAs(WorldStateCache.Snapshot.EMPTY);
    }

    @Test
    void emptySnapshotIsRecognisedAsEmpty() {
        assertThat(WorldStateCache.Snapshot.EMPTY.isEmpty()).isTrue();
    }

    @Test
    void nonEmptySnapshotIsNotEmpty() {
        WorldStateCache.Snapshot snap = fullSnapshot();
        assertThat(snap.isEmpty()).isFalse();
    }

    // --- update / get round-trip ---

    @Test
    void updateReplacesSnapshot() {
        var cache = new WorldStateCache();
        WorldStateCache.Snapshot snap = fullSnapshot();
        cache.update(snap);
        assertThat(cache.get()).isSameAs(snap);
    }

    @Test
    void updateRejectsNull() {
        var cache = new WorldStateCache();
        assertThatThrownBy(() -> cache.update(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- filter helpers on Snapshot ---

    @Test
    void threatsWithinRadiusFiltersCorrectly() {
        var close = new ThreatInfo("Creeper", new Location(5, 64, 5), 5.0, "N");
        var far = new ThreatInfo("Zombie", new Location(50, 64, 50), 50.0, "SE");
        WorldStateCache.Snapshot snap = snapshotWithThreats(List.of(close, far));

        assertThat(snap.threatsWithinRadius(10)).containsExactly(close);
        assertThat(snap.threatsWithinRadius(100)).containsExactlyInAnyOrder(close, far);
        assertThat(snap.threatsWithinRadius(1)).isEmpty();
    }

    @Test
    void entitiesWithinRadiusFiltersTypeAndRadius() {
        var pig = EntityInfo.builder().type("Pig").location(new Location(3, 64, 3))
                .distance(3.0).direction("N").build();
        var cow = EntityInfo.builder().type("Cow").location(new Location(4, 64, 4))
                .distance(4.0).direction("NE").build();
        var farPig = EntityInfo.builder().type("Pig").location(new Location(40, 64, 40))
                .distance(40.0).direction("SE").build();
        WorldStateCache.Snapshot snap = snapshotWithEntities(List.of(pig, cow, farPig));

        assertThat(snap.entitiesWithinRadius("Pig", 10)).containsExactly(pig);
        assertThat(snap.entitiesWithinRadius("Cow", 10)).containsExactly(cow);
        assertThat(snap.entitiesWithinRadius("pig", 10)).containsExactly(pig); // case-insensitive
        assertThat(snap.entitiesWithinRadius("Pig", 50)).containsExactlyInAnyOrder(pig, farPig);
    }

    @Test
    void playersWithinRadiusFiltersCorrectly() {
        var near = PlayerInfo.builder().name("Alice").location(new Location(2, 64, 2))
                .distance(2.0).direction("N").build();
        var distant = PlayerInfo.builder().name("Bob").location(new Location(100, 64, 100))
                .distance(100.0).direction("SE").build();
        WorldStateCache.Snapshot snap = snapshotWithPlayers(List.of(near, distant));

        assertThat(snap.playersWithinRadius(10)).containsExactly(near);
        assertThat(snap.playersWithinRadius(200)).containsExactlyInAnyOrder(near, distant);
    }

    // --- snapshot lists are immutable (Fix 2) ---

    @Test
    void snapshotListsAreImmutable() {
        var threat = new ThreatInfo("Creeper", new Location(5, 64, 5), 5.0, "N");
        WorldStateCache.Snapshot snap = snapshotWithThreats(List.of(threat));
        var cache = new WorldStateCache();
        cache.update(snap);

        WorldStateCache.Snapshot cached = cache.get();
        // Mutating the returned list must throw — callers must not be able to corrupt the cache.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cached.threats().add(
                        new ThreatInfo("Zombie", new Location(10, 64, 10), 10.0, "NE")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- concurrency: 100 readers + 1 writer, no torn reads ---

    @Test
    void concurrentReadersAndWriterSeeConsistentSnapshot() throws InterruptedException {
        var cache = new WorldStateCache();
        int readerCount = 100;
        AtomicInteger tornReadCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(readerCount + 1);

        WorldStateCache.Snapshot snap1 = fullSnapshot();
        WorldStateCache.Snapshot snap2 = fullSnapshot();

        // Writer: rapidly alternates between two snapshots 500 times
        ExecutorService writer = Executors.newSingleThreadExecutor();
        writer.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 500; i++) {
                    cache.update(i % 2 == 0 ? snap1 : snap2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        // Readers: each reads the snapshot 200 times and verifies it is one of the two
        // valid snapshots (never a partially-written object).
        ExecutorService readers = Executors.newFixedThreadPool(readerCount);
        for (int r = 0; r < readerCount; r++) {
            readers.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 200; i++) {
                        WorldStateCache.Snapshot observed = cache.get();
                        // A valid read is EMPTY (initial), snap1, or snap2 — the reference
                        // must be one of these three. Any other object would indicate a torn write.
                        if (observed != WorldStateCache.Snapshot.EMPTY
                                && observed != snap1
                                && observed != snap2) {
                            tornReadCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads simultaneously
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(finished).isTrue();
        assertThat(tornReadCount.get())
                .as("No torn reads expected — volatile guarantees atomic reference swap")
                .isZero();

        writer.shutdown();
        readers.shutdown();
    }

    // --- construction helpers ---

    private static WorldStateCache.Snapshot fullSnapshot() {
        return new WorldStateCache.Snapshot(
                new Location(10, 64, 10),
                new HealthStatus(20f, 20f, 20, 0),
                List.of(),
                List.of(),
                List.of(),
                "minecraft:dirt x64",
                6000,
                false,
                15
        );
    }

    private static WorldStateCache.Snapshot snapshotWithThreats(List<ThreatInfo> threats) {
        return new WorldStateCache.Snapshot(
                new Location(0, 64, 0),
                new HealthStatus(20f, 20f, 20, 0),
                List.of(),
                threats,
                List.of(),
                "",
                0, false, 0
        );
    }

    private static WorldStateCache.Snapshot snapshotWithEntities(List<EntityInfo> entities) {
        return new WorldStateCache.Snapshot(
                new Location(0, 64, 0),
                new HealthStatus(20f, 20f, 20, 0),
                entities,
                List.of(),
                List.of(),
                "",
                0, false, 0
        );
    }

    private static WorldStateCache.Snapshot snapshotWithPlayers(List<PlayerInfo> players) {
        return new WorldStateCache.Snapshot(
                new Location(0, 64, 0),
                new HealthStatus(20f, 20f, 20, 0),
                List.of(),
                List.of(),
                players,
                "",
                0, false, 0
        );
    }
}
