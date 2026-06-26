package com.mcagent.core.reflex;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.HealthStatus;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import com.mcagent.core.telemetry.Counters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurvivalReflexesTest {

    @Mock
    private BotOperations bot;

    private Counters counters;
    private BotProperties botProps;
    private SurvivalReflexes reflexes;

    // A nearby threat used across multiple tests.
    private static final ThreatInfo NEARBY_ZOMBIE =
            new ThreatInfo("Zombie", new Location(10, 64, 10), 8.0, "NE");

    @BeforeEach
    void setUp() {
        counters = new Counters();
        botProps = new BotProperties();
        // ReflexProperties defaults: enabled=true, eatThreshold=8, healthCriticalPct=30,
        // fleeDistance=16, darkLightThreshold=7.
        reflexes = new SurvivalReflexes(bot, counters, botProps);
    }

    // -----------------------------------------------------------------------
    // Rule 1: Critical HP + threats present -> FLEE
    // -----------------------------------------------------------------------

    @Test
    void rule1_criticalHpWithThreat_flees() {
        // 5/20 = 25% < 30% critical threshold
        ReflexSnapshot snap = snapshot(health(5f, 20f, 15), false, 10, List.of(NEARBY_ZOMBIE));

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.FLEE);
        verify(bot).setSafetyMode(true);
        verify(bot).fleeFrom(NEARBY_ZOMBIE.location(), 16);
        assertThat(counters.get("reflex.flee")).isEqualTo(1);
    }

    @Test
    void rule1_criticalHpNoThreats_doesNotFlee() {
        // HP critical but no threats — flee must not fire.
        ReflexSnapshot snap = snapshot(health(5f, 20f, 15), false, 10, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isNotEqualTo(SurvivalReflexes.Action.FLEE);
        verify(bot, never()).fleeFrom(any(), anyInt());
    }

    @Test
    void hpCritical_noThreats_returnsCriticalNoThreat() {
        // HP critical with no nearby threats (Rule 1b): bot is hurt by environment
        // (falling, fire, drowning). Safety mode must engage; CRITICAL_NO_THREAT returned
        // so the outer caller can publish a framework message.
        ReflexSnapshot snap = snapshot(health(5f, 20f, 15), false, 10, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.CRITICAL_NO_THREAT);
        verify(bot).setSafetyMode(true);
        verify(bot, never()).fleeFrom(any(), anyInt());
        assertThat(counters.get("reflex.critical_no_threat")).isEqualTo(1);
    }

    @Test
    void rule1_healthyHpWithThreat_doesNotFlee() {
        // 18/20 = 90% > 30% threshold; hunger fine; daytime -> NONE.
        ReflexSnapshot snap = snapshot(health(18f, 20f, 15), false, 10, List.of(NEARBY_ZOMBIE));

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isNotEqualTo(SurvivalReflexes.Action.FLEE);
        verify(bot, never()).setSafetyMode(true);
    }

    @Test
    void rule1_usesClosestThreatAsFleeTarget() {
        ThreatInfo far = new ThreatInfo("Skeleton", new Location(30, 64, 30), 25.0, "SE");
        ReflexSnapshot snap = snapshot(health(5f, 20f, 15), false, 10,
                List.of(NEARBY_ZOMBIE, far));

        reflexes.tickReflexes(snap);

        // threats list is in order; index 0 = closest
        verify(bot).fleeFrom(NEARBY_ZOMBIE.location(), 16);
    }

    // -----------------------------------------------------------------------
    // Rule 2: Low hunger -> EAT
    // -----------------------------------------------------------------------

    @Test
    void rule2_lowHungerFoodAvailable_eats() {
        when(bot.eat(isNull())).thenReturn(true);
        // foodLevel=4, well below eatThreshold=8; HP is fine; no threats
        ReflexSnapshot snap = snapshot(health(18f, 20f, 4), false, 10, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.EAT);
        verify(bot).eat(null);
        assertThat(counters.get("reflex.eat")).isEqualTo(1);
    }

    @Test
    void rule2_lowHungerNoFood_returnsNone() {
        when(bot.eat(isNull())).thenReturn(false);
        ReflexSnapshot snap = snapshot(health(18f, 20f, 4), false, 10, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.NONE);
        verify(bot).eat(null);
        assertThat(counters.get("reflex.eat")).isEqualTo(0);
    }

    @Test
    void rule2_hungerAboveThreshold_doesNotEat() {
        // foodLevel=15, above eatThreshold=8
        ReflexSnapshot snap = snapshot(health(18f, 20f, 15), false, 10, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.NONE);
        verify(bot, never()).eat(anyString());
        verify(bot, never()).eat(isNull());
    }

    // -----------------------------------------------------------------------
    // Rule 3: Night + dark + has torch -> PLACE_TORCH
    // -----------------------------------------------------------------------

    @Test
    void rule3_nightDarkHasTorch_placesTorh() {
        when(bot.hasItem("minecraft:torch", 1)).thenReturn(true);
        ReflexSnapshot snap = snapshot(health(18f, 20f, 15), true, 3, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.PLACE_TORCH);
        verify(bot).placeTorch();
        assertThat(counters.get("reflex.torch")).isEqualTo(1);
    }

    @Test
    void rule3_daytime_doesNotPlaceTorch() {
        ReflexSnapshot snap = snapshot(health(18f, 20f, 15), false, 3, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.NONE);
        verify(bot, never()).placeTorch();
    }

    @Test
    void rule3_nightBrightArea_doesNotPlaceTorch() {
        // lightLevel=12, above darkLightThreshold=7
        ReflexSnapshot snap = snapshot(health(18f, 20f, 15), true, 12, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.NONE);
        verify(bot, never()).placeTorch();
    }

    @Test
    void rule3_nightDarkNoTorch_doesNotPlaceTorch() {
        when(bot.hasItem("minecraft:torch", 1)).thenReturn(false);
        ReflexSnapshot snap = snapshot(health(18f, 20f, 15), true, 3, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.NONE);
        verify(bot, never()).placeTorch();
    }

    // -----------------------------------------------------------------------
    // Priority: Rule 1 short-circuits Rule 2
    // -----------------------------------------------------------------------

    @Test
    void priority_criticalHpAndHungry_onlyFleeFires() {
        // Both rule 1 (HP critical + threat) and rule 2 (hunger low) conditions are met.
        // Only FLEE should fire; eat() must not be called.
        ReflexSnapshot snap = snapshot(health(5f, 20f, 4), false, 10,
                List.of(NEARBY_ZOMBIE));

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.FLEE);
        verify(bot).setSafetyMode(true);
        verify(bot).fleeFrom(NEARBY_ZOMBIE.location(), 16);
        verify(bot, never()).eat(any());
        verify(bot, never()).eat(isNull());
    }

    @Test
    void priority_hungryAndNightDark_onlyEatFires() {
        // Rule 2 (hungry) and rule 3 (night/dark/torch) conditions both met.
        // Rule 2 has higher priority: only EAT should fire; hasItem() for torch must not be reached.
        when(bot.eat(isNull())).thenReturn(true);
        // No stub for hasItem — if rule 3 fires it would need it; absence keeps the test strict.
        ReflexSnapshot snap = snapshot(health(18f, 20f, 4), true, 3, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.EAT);
        verify(bot, never()).placeTorch();
    }

    // -----------------------------------------------------------------------
    // Negative / no-op case
    // -----------------------------------------------------------------------

    @Test
    void allConditionsGood_returnsNone() {
        // High HP, good food, daytime, no threats.
        ReflexSnapshot snap = snapshot(health(18f, 20f, 15), false, 12, List.of());

        SurvivalReflexes.Action action = reflexes.tickReflexes(snap);

        assertThat(action).isEqualTo(SurvivalReflexes.Action.NONE);
        verify(bot, never()).setSafetyMode(true);
        verify(bot, never()).eat(any());
        verify(bot, never()).eat(isNull());
        verify(bot, never()).placeTorch();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static HealthStatus health(float hp, float maxHp, int food) {
        return new HealthStatus(hp, maxHp, food, 0);
    }

    private static ReflexSnapshot snapshot(HealthStatus health, boolean isNight,
                                           int lightLevel, List<ThreatInfo> threats) {
        int worldTime = isNight ? 14000 : 6000;
        return new ReflexSnapshot(health, worldTime, isNight, lightLevel, threats);
    }
}
