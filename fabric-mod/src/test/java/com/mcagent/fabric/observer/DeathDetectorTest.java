package com.mcagent.fabric.observer;

import com.mcagent.core.service.BotOperations.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DeathDetector} — the pure-logic death/respawn state machine.
 */
class DeathDetectorTest {

    private DeathDetector detector;

    private static final Location LOC_A = new Location(10, 64, 20);
    private static final Location LOC_B = new Location(0, 64, 0);

    @BeforeEach
    void setUp() {
        detector = new DeathDetector();
    }

    @Test
    void initialState_considersBotAlive() {
        assertThat(detector.isConsideredAlive()).isTrue();
    }

    @Test
    void tick_noTransition_whenAliveAndNotDead_returnsNull() {
        Object result = detector.tick(false, LOC_A);

        assertThat(result).isNull();
        assertThat(detector.isConsideredAlive()).isTrue();
    }

    @Test
    void tick_aliveToDeadTransition_returnsDeathEvent() {
        Object result = detector.tick(true, LOC_A);

        assertThat(result).isInstanceOf(DeathDetector.DeathEvent.class);
        DeathDetector.DeathEvent event = (DeathDetector.DeathEvent) result;
        assertThat(event.location()).isEqualTo(LOC_A);
        assertThat(detector.isConsideredAlive()).isFalse();
    }

    @Test
    void tick_deadToAliveTransition_returnsRespawnEvent() {
        detector.tick(true, LOC_A);  // death edge

        Object result = detector.tick(false, LOC_B);

        assertThat(result).isInstanceOf(DeathDetector.RespawnEvent.class);
        DeathDetector.RespawnEvent event = (DeathDetector.RespawnEvent) result;
        assertThat(event.location()).isEqualTo(LOC_B);
        assertThat(detector.isConsideredAlive()).isTrue();
    }

    @Test
    void tick_noTransition_whenDeadAndStillDead_returnsNull() {
        detector.tick(true, LOC_A);  // death edge

        Object result = detector.tick(true, LOC_A);  // still dead — no transition

        assertThat(result).isNull();
        assertThat(detector.isConsideredAlive()).isFalse();
    }

    @Test
    void tick_noTransition_afterRespawn_whileAlive_returnsNull() {
        detector.tick(true, LOC_A);   // death
        detector.tick(false, LOC_B);  // respawn

        Object result = detector.tick(false, LOC_B);  // still alive — no transition

        assertThat(result).isNull();
        assertThat(detector.isConsideredAlive()).isTrue();
    }

    @Test
    void tick_consecutiveDeathRespawnCycles_detectBothEdges() {
        // First cycle
        assertThat(detector.tick(true, LOC_A)).isInstanceOf(DeathDetector.DeathEvent.class);
        assertThat(detector.tick(false, LOC_B)).isInstanceOf(DeathDetector.RespawnEvent.class);

        // Second cycle
        assertThat(detector.tick(true, LOC_A)).isInstanceOf(DeathDetector.DeathEvent.class);
        assertThat(detector.tick(false, LOC_B)).isInstanceOf(DeathDetector.RespawnEvent.class);
    }
}
