package com.mcagent.fabric.observer;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.observer.BotMode;
import com.mcagent.core.reflex.ReflexSnapshot;
import com.mcagent.core.reflex.SurvivalReflexes;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.HealthStatus;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import com.mcagent.core.service.ops.InventoryOperations;
import com.mcagent.core.service.ops.ObservationOperations;
import com.mcagent.core.task.Plan;
import com.mcagent.core.task.TaskExecutor;
import com.mcagent.core.task.TaskSnapshot;
import com.mcagent.core.task.TaskState;
import com.mcagent.fabric.cache.WorldStateCache;
import com.mcagent.fabric.queue.BotEventQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutonomousObserverTest {

    @Mock
    private ObservationOperations botOperations;

    @Mock
    private BotEventQueue eventQueue;

    @Mock
    private SurvivalReflexes survivalReflexes;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private InventoryOperations inventoryOps;

    private BotProperties.ObservationProperties config;
    private AutonomousObserver observer;

    @BeforeEach
    void setUp() {
        config = new BotProperties.ObservationProperties();
        config.setMode(BotMode.WATCH);
        config.setScanIntervalTicks(5);
        config.setThreatRadius(32);
        config.setPassiveRadius(16);
        config.setMessageMode("individual");
        config.setDebounceSeconds(10);
        config.setTrackPassiveMobs(true);
        config.setPassiveMobTypes(List.of("Pig", "Cow"));

        observer = new AutonomousObserver(botOperations, eventQueue, config);
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThatThrownBy(() -> new AutonomousObserver(null, eventQueue, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("botOperations");
        assertThatThrownBy(() -> new AutonomousObserver(botOperations, null, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventQueue");
        assertThatThrownBy(() -> new AutonomousObserver(botOperations, eventQueue, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    @Test
    void noScanBeforeIntervalElapsed() {
        observer.onTick();
        observer.onTick();
        observer.onTick();
        observer.onTick();

        verify(botOperations, never()).getNearbyThreats(anyInt());
    }

    @Test
    void threatDetectionPublishesFrameworkMessage() {
        var threat = new ThreatInfo("Zombie", new BotOperations.Location(10, 64, 20), 8.0, "NE");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        tickToScan();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(eventQueue).publishFramework(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo("Threat detected: Zombie at (10, 64, 20), 8 blocks NE");
    }

    @Test
    void debounceSuppressesDuplicateThreat() {
        var threat = new ThreatInfo("Zombie", new BotOperations.Location(10, 64, 20), 8.0, "NE");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        tickToScan();
        tickToScan();

        verify(eventQueue).publishFramework(anyString());
    }

    @Test
    void passiveMobScanningPublishesOpportunity() {
        var pig = EntityInfo.builder()
                .type("Pig")
                .location(new BotOperations.Location(5, 64, 5))
                .distance(4.0)
                .direction("N")
                .build();
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of());
        when(botOperations.getNearbyEntities("Pig", 16)).thenReturn(List.of(pig));
        when(botOperations.getNearbyEntities("Cow", 16)).thenReturn(List.of());

        tickToScan();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(eventQueue).publishFramework(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo("Opportunity: Pig at (5, 64, 5), 4 blocks N");
    }

    @Test
    void activeModeTriggersUrgentFramework() {
        observer.setBotMode(BotMode.AUTO);
        var threat = new ThreatInfo("Creeper", new BotOperations.Location(15, 64, 15), 5.0, "E");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        tickToScan();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(eventQueue).triggerUrgentFramework(captor.capture());
        assertThat(captor.getValue()).contains("Creeper");
    }

    @Test
    void passiveModeCallsPublishFramework() {
        var threat = new ThreatInfo("Skeleton", new BotOperations.Location(20, 64, 20), 12.0, "SE");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        tickToScan();

        verify(eventQueue).publishFramework(anyString());
    }

    @Test
    void disablePreventsScanning() {
        observer.setEnabled(false);

        tickToScan();

        verify(botOperations, never()).getNearbyThreats(anyInt());
    }

    @Test
    void enableAllowsScanningAfterBeingDisabled() {
        observer.setEnabled(false);
        tickToScan();
        verify(botOperations, never()).getNearbyThreats(anyInt());

        observer.setEnabled(true);
        var threat = new ThreatInfo("Zombie", new BotOperations.Location(10, 64, 20), 8.0, "NE");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        tickToScan();

        verify(eventQueue).publishFramework(anyString());
    }

    @Test
    void summaryModeAggregatesAllEntities() {
        observer.setMessageMode("summary");
        var threat = new ThreatInfo("Zombie", new BotOperations.Location(10, 64, 20), 8.0, "NE");
        var pig = EntityInfo.builder()
                .type("Pig")
                .location(new BotOperations.Location(5, 64, 5))
                .distance(4.0)
                .direction("N")
                .build();
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));
        when(botOperations.getNearbyEntities("Pig", 16)).thenReturn(List.of(pig));
        when(botOperations.getNearbyEntities("Cow", 16)).thenReturn(List.of());

        tickToScan();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(eventQueue).publishFramework(captor.capture());
        assertThat(captor.getValue()).startsWith("Status scan: threats:");
        assertThat(captor.getValue()).contains("Zombie");
        assertThat(captor.getValue()).contains("Pig");
    }

    @Test
    void individualModePublishesOnlyClosestThreat() {
        var zombie = new ThreatInfo("Zombie", new BotOperations.Location(10, 64, 20), 5.0, "NE");
        var skeleton = new ThreatInfo("Skeleton", new BotOperations.Location(30, 64, 30), 15.0, "SE");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(zombie, skeleton));

        tickToScan();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(eventQueue).publishFramework(captor.capture());
        assertThat(captor.getValue()).contains("Zombie");
        assertThat(captor.getValue()).doesNotContain("Skeleton");
    }

    // -----------------------------------------------------------------------
    // Reflex layer gating tests
    // -----------------------------------------------------------------------

    @Test
    void activeMode_warmCache_tickReflexesIsCalled() {
        BotProperties botProps = new BotProperties();
        botProps.getReflex().setEnabled(true);

        // Build a warm (non-EMPTY) cache snapshot.
        WorldStateCache cache = new WorldStateCache();
        WorldStateCache.Snapshot warmSnap = new WorldStateCache.Snapshot(
                new BotOperations.Location(0, 64, 0),
                new HealthStatus(18f, 20f, 15, 0),
                List.of(),
                List.of(),
                List.of(),
                "bread x4",
                6000,
                false,
                12
        );
        cache.update(warmSnap);

        // Use full constructor with active mode.
        config.setMode(BotMode.AUTO);
        observer = new AutonomousObserver(
                botOperations, eventQueue, config,
                new com.mcagent.core.telemetry.JobTracker(),
                new com.mcagent.core.telemetry.Counters(),
                null,
                null,
                () -> null,
                cache,
                survivalReflexes,
                botProps,
                null,   // no task executor in reflex tests
                null,   // no inventoryOps in reflex tests
                null,   // no autoModeDriver in reflex tests
                null);  // no routineService in reflex tests

        // Cache is warm so scan() reads from it; no bridge calls are needed.
        when(survivalReflexes.tickReflexes(any(ReflexSnapshot.class)))
                .thenReturn(SurvivalReflexes.Action.NONE);

        tickToScan();

        verify(survivalReflexes).tickReflexes(any(ReflexSnapshot.class));
    }

    @Test
    void passiveMode_warmCache_tickReflexesIsNeverCalled() {
        BotProperties botProps = new BotProperties();
        botProps.getReflex().setEnabled(true);

        WorldStateCache cache = new WorldStateCache();
        WorldStateCache.Snapshot warmSnap = new WorldStateCache.Snapshot(
                new BotOperations.Location(0, 64, 0),
                new HealthStatus(18f, 20f, 15, 0),
                List.of(),
                List.of(),
                List.of(),
                "",
                6000,
                false,
                12
        );
        cache.update(warmSnap);

        config.setMode(BotMode.WATCH);
        observer = new AutonomousObserver(
                botOperations, eventQueue, config,
                new com.mcagent.core.telemetry.JobTracker(),
                new com.mcagent.core.telemetry.Counters(),
                null,
                null,
                () -> null,
                cache,
                survivalReflexes,
                botProps,
                null,   // no task executor in reflex tests
                null,   // no inventoryOps in reflex tests
                null,   // no autoModeDriver in reflex tests
                null);  // no routineService in reflex tests

        // Cache is warm; scan finds no threats/opportunities and returns early — no bridge calls.
        tickToScan();

        verify(survivalReflexes, never()).tickReflexes(any());
    }

    @Test
    void activeMode_reflexDisabledInConfig_tickReflexesIsNeverCalled() {
        BotProperties botProps = new BotProperties();
        botProps.getReflex().setEnabled(false);   // disabled

        WorldStateCache cache = new WorldStateCache();
        WorldStateCache.Snapshot warmSnap = new WorldStateCache.Snapshot(
                new BotOperations.Location(0, 64, 0),
                new HealthStatus(18f, 20f, 15, 0),
                List.of(),
                List.of(),
                List.of(),
                "",
                6000,
                false,
                12
        );
        cache.update(warmSnap);

        config.setMode(BotMode.AUTO);
        observer = new AutonomousObserver(
                botOperations, eventQueue, config,
                new com.mcagent.core.telemetry.JobTracker(),
                new com.mcagent.core.telemetry.Counters(),
                null,
                null,
                () -> null,
                cache,
                survivalReflexes,
                botProps,
                null,   // no task executor in reflex tests
                null,   // no inventoryOps in reflex tests
                null,   // no autoModeDriver in reflex tests
                null);  // no routineService in reflex tests

        // Cache is warm; scan finds no threats/opportunities and returns early — no bridge calls.
        tickToScan();

        verify(survivalReflexes, never()).tickReflexes(any());
    }

    @Test
    void activeMode_coldCache_tickReflexesIsNeverCalled() {
        // Cache is EMPTY (cold boot) — reflex tick must be skipped.
        BotProperties botProps = new BotProperties();
        botProps.getReflex().setEnabled(true);

        WorldStateCache cache = new WorldStateCache();  // snapshot = EMPTY

        config.setMode(BotMode.AUTO);
        observer = new AutonomousObserver(
                botOperations, eventQueue, config,
                new com.mcagent.core.telemetry.JobTracker(),
                new com.mcagent.core.telemetry.Counters(),
                null,
                null,
                () -> null,
                cache,
                survivalReflexes,
                botProps,
                null,   // no task executor in reflex tests
                null,   // no inventoryOps in reflex tests
                null,   // no autoModeDriver in reflex tests
                null);  // no routineService in reflex tests

        when(botOperations.getNearbyThreats(anyInt())).thenReturn(List.of());
        when(botOperations.getNearbyEntities(anyString(), anyInt())).thenReturn(List.of());

        tickToScan();

        verify(survivalReflexes, never()).tickReflexes(any());
    }

    // -----------------------------------------------------------------------
    // Task-aware observer (A6) tests
    // -----------------------------------------------------------------------

    @Test
    void threatPublished_whenNoTaskActive() {
        // No active task — taskExecutor returns empty. Threat should publish normally.
        when(taskExecutor.currentTask()).thenReturn(Optional.empty());
        var threat = new ThreatInfo("Zombie", new BotOperations.Location(10, 64, 20), 20.0, "NE");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        AutonomousObserver obs = observerWithTaskExecutor(taskExecutor);
        tickToScan(obs);

        verify(eventQueue).publishFramework(anyString());
    }

    @Test
    void threatSuppressed_whenLowRiskTaskActive_andThreatBeyondRadius() {
        // Task is RUNNING (low-risk), threat is 20 blocks away — beyond default radius of 8.
        // Expect: no publish, counter incremented.
        TaskSnapshot snapshot = runningTaskSnapshot();
        when(taskExecutor.currentTask()).thenReturn(Optional.of(snapshot));
        var threat = new ThreatInfo("Zombie", new BotOperations.Location(10, 64, 20), 20.0, "NE");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        com.mcagent.core.telemetry.Counters counters = new com.mcagent.core.telemetry.Counters();
        AutonomousObserver obs = observerWithTaskExecutorAndCounters(taskExecutor, counters);
        tickToScan(obs);

        verify(eventQueue, never()).publishFramework(anyString());
        verify(eventQueue, never()).triggerUrgentFramework(anyString());
        assertThat(counters.get("observation.suppressed")).isEqualTo(1);
    }

    @Test
    void threatPublished_whenLowRiskTaskActive_butThreatWithinRadius() {
        // Task is RUNNING, but threat is only 5 blocks away — within the 8-block radius.
        // Expect: publish fires despite the active task.
        TaskSnapshot snapshot = runningTaskSnapshot();
        when(taskExecutor.currentTask()).thenReturn(Optional.of(snapshot));
        var threat = new ThreatInfo("Creeper", new BotOperations.Location(3, 64, 4), 5.0, "SW");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        AutonomousObserver obs = observerWithTaskExecutor(taskExecutor);
        tickToScan(obs);

        verify(eventQueue).publishFramework(anyString());
    }

    @Test
    void opportunitiesPublishedRegardlessOfTask() {
        // Task is RUNNING — opportunities (passive mobs) must still be published regardless.
        // Note: taskExecutor.currentTask() is not stubbed here because suppressLowRiskThreats
        // short-circuits when the threats list is empty, so it is never called.
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of());
        var pig = EntityInfo.builder()
                .type("Pig")
                .location(new BotOperations.Location(5, 64, 5))
                .distance(4.0)
                .direction("N")
                .build();
        when(botOperations.getNearbyEntities("Pig", 16)).thenReturn(List.of(pig));
        when(botOperations.getNearbyEntities("Cow", 16)).thenReturn(List.of());

        AutonomousObserver obs = observerWithTaskExecutor(taskExecutor);
        tickToScan(obs);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(eventQueue).publishFramework(captor.capture());
        assertThat(captor.getValue()).contains("Pig");
    }

    /** Builds a running TaskSnapshot with a minimal Plan for testing. */
    private TaskSnapshot runningTaskSnapshot() {
        Plan plan = new Plan(UUID.randomUUID(), "test plan", "player",
                Instant.now(), List.of(),
                new com.mcagent.core.task.TerminationPolicy(java.time.Duration.ofHours(3), true));
        return new TaskSnapshot(plan.id(), plan, TaskState.RUNNING, 0, "",
                Instant.now(), Optional.empty());
    }

    /**
     * Builds a full-constructor {@link AutonomousObserver} with the given {@link TaskExecutor}
     * and default shared test infrastructure (shared {@code eventQueue}, {@code botOperations},
     * {@code config}).
     */
    private AutonomousObserver observerWithTaskExecutor(TaskExecutor executor) {
        return new AutonomousObserver(
                botOperations, eventQueue, config,
                new com.mcagent.core.telemetry.JobTracker(),
                new com.mcagent.core.telemetry.Counters(),
                null,
                null,
                () -> null,
                null,
                null,
                null,
                executor,
                null,   // no inventoryOps
                null,   // no autoModeDriver
                null);  // no routineService
    }

    /**
     * Like {@link #observerWithTaskExecutor} but uses a caller-supplied {@link
     * com.mcagent.core.telemetry.Counters} so tests can assert on counter state.
     */
    private AutonomousObserver observerWithTaskExecutorAndCounters(
            TaskExecutor executor, com.mcagent.core.telemetry.Counters counters) {
        return new AutonomousObserver(
                botOperations, eventQueue, config,
                new com.mcagent.core.telemetry.JobTracker(),
                counters,
                null,
                null,
                () -> null,
                null,
                null,
                null,
                executor,
                null,   // no inventoryOps
                null,   // no autoModeDriver
                null);  // no routineService
    }

    private void tickToScan(AutonomousObserver obs) {
        int interval = config.getScanIntervalTicks();
        for (int i = 0; i < interval; i++) {
            obs.onTick();
        }
    }

    private void tickToScan() {
        int interval = config.getScanIntervalTicks();
        for (int i = 0; i < interval; i++) {
            observer.onTick();
        }
    }

    // -----------------------------------------------------------------------
    // Heartbeat emission tests (A3)
    // -----------------------------------------------------------------------

    @Test
    void heartbeat_emittedWhenTaskRunningAndConditionsMet() {
        // Task started 2 minutes ago — past the grace period.
        Instant startedAt = Instant.now().minus(Duration.ofMinutes(2));
        TaskSnapshot snap = longRunningTaskSnapshot(startedAt);
        when(taskExecutor.currentTask()).thenReturn(Optional.of(snap));

        // Observer position / health for heartbeat formatting.
        when(botOperations.getCurrentPosition()).thenReturn(new BotOperations.Location(0, -32, 0));
        when(botOperations.getHealthStatus()).thenReturn(new HealthStatus(18f, 20f, 15, 0));
        when(botOperations.getNearbyThreats(anyInt())).thenReturn(List.of());
        when(botOperations.getNearbyEntities(anyString(), anyInt())).thenReturn(List.of());

        BotProperties botProps = new BotProperties();
        // Set very short grace/cooldown so the test fires immediately.
        botProps.getJob().setHeartbeatGraceSeconds(0);
        botProps.getJob().setHeartbeatCooldownSeconds(0);
        botProps.getJob().setSuppressIfRecentChatSeconds(0);

        com.mcagent.core.telemetry.JobTracker jobTracker = new com.mcagent.core.telemetry.JobTracker();
        com.mcagent.core.telemetry.Counters counters = new com.mcagent.core.telemetry.Counters();

        AutonomousObserver obs = new AutonomousObserver(
                botOperations, eventQueue, config,
                jobTracker, counters, null, null,
                () -> null, null, null, botProps, taskExecutor, inventoryOps, null, null);

        tickToScan(obs);

        // A heartbeat should have been enqueued on the outbound channel.
        verify(eventQueue).enqueueOutbound(anyString());
        assertThat(counters.get("heartbeat.sent")).isEqualTo(1);
    }

    @Test
    void heartbeat_suppressedDuringPlayerInteraction() {
        // Task started 2 minutes ago — past the grace period.
        Instant startedAt = Instant.now().minus(Duration.ofMinutes(2));
        TaskSnapshot snap = longRunningTaskSnapshot(startedAt);
        when(taskExecutor.currentTask()).thenReturn(Optional.of(snap));

        when(botOperations.getNearbyThreats(anyInt())).thenReturn(List.of());
        when(botOperations.getNearbyEntities(anyString(), anyInt())).thenReturn(List.of());

        BotProperties botProps = new BotProperties();
        botProps.getJob().setHeartbeatGraceSeconds(0);
        botProps.getJob().setHeartbeatCooldownSeconds(0);
        // Suppress window is 60s — player just interacted.
        botProps.getJob().setSuppressIfRecentChatSeconds(60);

        com.mcagent.core.telemetry.JobTracker jobTracker = new com.mcagent.core.telemetry.JobTracker();
        // Simulate a very recent player interaction.
        jobTracker.markPlayerInteraction();
        com.mcagent.core.telemetry.Counters counters = new com.mcagent.core.telemetry.Counters();

        AutonomousObserver obs = new AutonomousObserver(
                botOperations, eventQueue, config,
                jobTracker, counters, null, null,
                () -> null, null, null, botProps, taskExecutor, inventoryOps, null, null);

        tickToScan(obs);

        // Heartbeat must be suppressed — no outbound message.
        verify(eventQueue, never()).enqueueOutbound(anyString());
        assertThat(counters.get("heartbeat.sent")).isEqualTo(0);
    }

    /**
     * Builds a running {@link TaskSnapshot} with a given start time, using an empty step list.
     * The plan summary is used as the step description fallback in {@code formatHeartbeat}.
     */
    private TaskSnapshot longRunningTaskSnapshot(Instant startedAt) {
        Plan plan = new Plan(UUID.randomUUID(), "Mine iron ore", "player",
                startedAt, List.of(),
                new com.mcagent.core.task.TerminationPolicy(java.time.Duration.ofHours(3), true));
        return new TaskSnapshot(plan.id(), plan, TaskState.RUNNING, 0, "47/200 IRON_ORE",
                startedAt, Optional.empty());
    }

    // -----------------------------------------------------------------------
    // BotMode gating tests (Sprint 14 A1)
    // -----------------------------------------------------------------------

    @Test
    void standby_preventsScanning() {
        observer.setBotMode(BotMode.STANDBY);

        tickToScan();

        verify(botOperations, never()).getNearbyThreats(anyInt());
        verify(botOperations, never()).getNearbyEntities(anyString(), anyInt());
    }

    @Test
    void watch_allowsScanning() {
        observer.setBotMode(BotMode.WATCH);
        var threat = new ThreatInfo("Zombie", new BotOperations.Location(10, 64, 20), 8.0, "NE");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        tickToScan();

        verify(eventQueue).publishFramework(anyString());
    }

    @Test
    void auto_allowsScanning_andUsesUrgentChannel() {
        observer.setBotMode(BotMode.AUTO);
        var threat = new ThreatInfo("Creeper", new BotOperations.Location(5, 64, 5), 3.0, "S");
        when(botOperations.getNearbyThreats(32)).thenReturn(List.of(threat));

        tickToScan();

        verify(eventQueue).triggerUrgentFramework(anyString());
    }

    @Test
    void getBotMode_returnsCurrentMode() {
        observer.setBotMode(BotMode.AUTO);
        assertThat(observer.getBotMode()).isEqualTo(BotMode.AUTO);

        observer.setBotMode(BotMode.WATCH);
        assertThat(observer.getBotMode()).isEqualTo(BotMode.WATCH);

        observer.setBotMode(BotMode.STANDBY);
        assertThat(observer.getBotMode()).isEqualTo(BotMode.STANDBY);
    }

    @Test
    void auto_warmCache_tickReflexesIsCalled_viaBotMode() {
        // Same scenario as activeMode_warmCache_tickReflexesIsCalled but using setBotMode.
        BotProperties botProps = new BotProperties();
        botProps.getReflex().setEnabled(true);

        WorldStateCache cache = new WorldStateCache();
        WorldStateCache.Snapshot warmSnap = new WorldStateCache.Snapshot(
                new BotOperations.Location(0, 64, 0),
                new BotOperations.HealthStatus(18f, 20f, 15, 0),
                List.of(), List.of(), List.of(), "bread x4", 6000, false, 12);
        cache.update(warmSnap);

        config.setMode(BotMode.AUTO);
        AutonomousObserver obs = new AutonomousObserver(
                botOperations, eventQueue, config,
                new com.mcagent.core.telemetry.JobTracker(),
                new com.mcagent.core.telemetry.Counters(),
                null, null, () -> null,
                cache, survivalReflexes, botProps, null, null, null, null);

        when(survivalReflexes.tickReflexes(any(ReflexSnapshot.class)))
                .thenReturn(SurvivalReflexes.Action.NONE);

        tickToScan(obs);

        verify(survivalReflexes).tickReflexes(any(ReflexSnapshot.class));
    }

    @Test
    void watch_warmCache_reflexesNeverCalled_viaBotMode() {
        // WATCH mode: reflexes must be off (Sprint 14 A1 scope).
        BotProperties botProps = new BotProperties();
        botProps.getReflex().setEnabled(true);

        WorldStateCache cache = new WorldStateCache();
        WorldStateCache.Snapshot warmSnap = new WorldStateCache.Snapshot(
                new BotOperations.Location(0, 64, 0),
                new BotOperations.HealthStatus(18f, 20f, 15, 0),
                List.of(), List.of(), List.of(), "", 6000, false, 12);
        cache.update(warmSnap);

        config.setMode(BotMode.WATCH);
        AutonomousObserver obs = new AutonomousObserver(
                botOperations, eventQueue, config,
                new com.mcagent.core.telemetry.JobTracker(),
                new com.mcagent.core.telemetry.Counters(),
                null, null, () -> null,
                cache, survivalReflexes, botProps, null, null, null, null);

        tickToScan(obs);

        verify(survivalReflexes, never()).tickReflexes(any());
    }
}
