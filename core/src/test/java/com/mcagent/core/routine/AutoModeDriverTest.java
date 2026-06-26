package com.mcagent.core.routine;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.service.BotOperations.HealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AutoModeDriver}.
 *
 * Verifies the Sprint 14 v1 selection logic: night → nightShelter; otherwise no selection.
 */
class AutoModeDriverTest {

    private BotProperties botProperties;
    private AutoModeDriver driver;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        botProperties.getRoutine().setAutoDriverEnabled(true);
        botProperties.getRoutine().setBaseX(100);
        botProperties.getRoutine().setBaseY(64);
        botProperties.getRoutine().setBaseZ(200);
        driver = new AutoModeDriver(botProperties);
    }

    // -------------------------------------------------------------------------
    // Night-time selection
    // -------------------------------------------------------------------------

    @Test
    void pickRoutine_selectsNightShelter_whenIsNight() {
        HealthStatus health = new HealthStatus(18f, 20f, 15, 0);

        Optional<RoutineInvocation> result = driver.pickRoutine(health, 15000, true);

        assertThat(result).isPresent();
        assertThat(result.get().routineName()).isEqualTo("nightShelter");
    }

    @Test
    void pickRoutine_nightShelterParams_useConfiguredBaseCoords() {
        Optional<RoutineInvocation> result = driver.pickRoutine(null, 18000, true);

        assertThat(result).isPresent();
        RoutineInvocation invocation = result.get();
        assertThat(invocation.params()).containsEntry("baseX", 100);
        assertThat(invocation.params()).containsEntry("baseY", 64);
        assertThat(invocation.params()).containsEntry("baseZ", 200);
    }

    // -------------------------------------------------------------------------
    // Daytime — no selection in Sprint 14 v1
    // -------------------------------------------------------------------------

    @Test
    void pickRoutine_returnsEmpty_whenDaytime() {
        HealthStatus health = new HealthStatus(18f, 20f, 15, 0);

        Optional<RoutineInvocation> result = driver.pickRoutine(health, 6000, false);

        assertThat(result).isEmpty();
    }

    @Test
    void pickRoutine_returnsEmpty_withNullHealth_andDaytime() {
        Optional<RoutineInvocation> result = driver.pickRoutine(null, 1000, false);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Disabled driver
    // -------------------------------------------------------------------------

    @Test
    void pickRoutine_returnsEmpty_whenAutoDriverDisabled() {
        botProperties.getRoutine().setAutoDriverEnabled(false);

        Optional<RoutineInvocation> result = driver.pickRoutine(null, 15000, true);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void pickRoutine_worldTimeAtDusk_isNightFalse_returnsEmpty() {
        // worldTime=12000 is dusk, isNight=false
        Optional<RoutineInvocation> result = driver.pickRoutine(null, 12000, false);

        assertThat(result).isEmpty();
    }

    @Test
    void pickRoutine_worldTimeAtMidnight_isNightTrue_selectsNightShelter() {
        Optional<RoutineInvocation> result = driver.pickRoutine(null, 18000, true);

        assertThat(result).isPresent();
        assertThat(result.get().routineName()).isEqualTo("nightShelter");
    }
}
