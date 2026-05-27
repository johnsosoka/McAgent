package com.mcagent.fabric.observer;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import com.mcagent.fabric.queue.BotEventQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutonomousObserverTest {

    @Mock
    private BotOperations botOperations;

    @Mock
    private BotEventQueue eventQueue;

    private BotProperties.ObservationProperties config;
    private AutonomousObserver observer;

    @BeforeEach
    void setUp() {
        config = new BotProperties.ObservationProperties();
        config.setEnabled(true);
        config.setScanIntervalTicks(5);
        config.setThreatRadius(32);
        config.setPassiveRadius(16);
        config.setMode("passive");
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
        observer.setMode("active");
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

    private void tickToScan() {
        int interval = config.getScanIntervalTicks();
        for (int i = 0; i < interval; i++) {
            observer.onTick();
        }
    }
}
