package com.mcagent.core.task;

import com.mcagent.core.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PlanApprovalGate}.
 *
 * <p>Verifies threshold logic for step-count and total-duration gates.
 * Uses default threshold values (3 steps, 300 seconds) via real BotProperties.</p>
 */
class PlanApprovalGateTest {

    /**
     * Short step timeout — chosen so that 3 steps remain under the 300-second duration threshold.
     * 3 × 90s = 270s, which is under 300s.
     */
    private static final Duration SHORT = Duration.ofSeconds(90);

    /**
     * Medium step timeout — 3 steps × 110s = 330s, which exceeds the 300-second threshold
     * while keeping the step count at exactly 3 (at the threshold, not over it).
     */
    private static final Duration MEDIUM_OVER = Duration.ofSeconds(110);

    /** Long step timeout: 6 minutes — exceeds the 5-minute threshold on its own. */
    private static final Duration LONG = Duration.ofMinutes(6);

    private PlanApprovalGate gate;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        // Use default thresholds: 3 steps, 300 seconds (5 min)
        gate = new PlanApprovalGate(props);
    }

    // --- Step-count threshold ---

    @Test
    void requiresApproval_returnsFalse_forSingleShortStep() {
        Plan plan = planWith(List.of(navigate(SHORT)));

        assertThat(gate.requiresApproval(plan)).isFalse();
    }

    @Test
    void requiresApproval_returnsFalse_forExactlyThreeSteps() {
        // Threshold is "more than 3" — exactly 3 should not require approval.
        Plan plan = planWith(List.of(navigate(SHORT), navigate(SHORT), navigate(SHORT)));

        assertThat(gate.requiresApproval(plan)).isFalse();
    }

    @Test
    void requiresApproval_returnsTrue_forFourSteps() {
        Plan plan = planWith(List.of(
                navigate(SHORT), navigate(SHORT), navigate(SHORT), navigate(SHORT)));

        assertThat(gate.requiresApproval(plan)).isTrue();
    }

    // --- Duration threshold ---

    @Test
    void requiresApproval_returnsFalse_forSingleStepUnderDurationLimit() {
        // 2 minutes total — well under 300 seconds
        Plan plan = planWith(List.of(navigate(SHORT)));

        assertThat(gate.requiresApproval(plan)).isFalse();
    }

    @Test
    void requiresApproval_returnsTrue_forSingleStepExceedingDurationLimit() {
        // 6 minutes (360 seconds) > 300-second threshold
        Plan plan = planWith(List.of(navigate(LONG)));

        assertThat(gate.requiresApproval(plan)).isTrue();
    }

    @Test
    void requiresApproval_returnsTrue_whenCumulativeDurationExceedsLimit() {
        // 3 steps × 110s = 330 s > 300 s, but step count == 3 (at threshold, not over).
        // Verifies the duration gate fires independently of the step-count gate.
        Plan plan = planWith(List.of(
                navigate(MEDIUM_OVER), navigate(MEDIUM_OVER), navigate(MEDIUM_OVER)));

        assertThat(gate.requiresApproval(plan)).isTrue();
    }

    // --- Custom thresholds ---

    @Test
    void requiresApproval_respectsCustomStepThreshold() {
        BotProperties props = new BotProperties();
        props.getPlan().setApprovalStepThreshold(1);
        PlanApprovalGate customGate = new PlanApprovalGate(props);

        Plan twostep = planWith(List.of(navigate(SHORT), navigate(SHORT)));

        assertThat(customGate.requiresApproval(twostep)).isTrue();
    }

    @Test
    void requiresApproval_respectsCustomDurationThreshold() {
        BotProperties props = new BotProperties();
        props.getPlan().setApprovalDurationSeconds(60);
        PlanApprovalGate customGate = new PlanApprovalGate(props);

        // 2-minute step exceeds 60-second custom threshold
        Plan plan = planWith(List.of(navigate(SHORT)));

        assertThat(customGate.requiresApproval(plan)).isTrue();
    }

    // --- Null timeout handling (Fix 1) ---

    @Test
    void requiresApproval_handlesNullStepTimeouts() {
        // NavigateStep with null timeout — should contribute 0 to the duration sum, not NPE.
        NavigateStep nullTimeoutStep = new NavigateStep(0, 64, 0, null, RetryPolicy.NONE);
        Plan plan = planWith(List.of(nullTimeoutStep));

        // Must not throw NullPointerException; 0 seconds is under the 300-second threshold.
        assertThat(gate.requiresApproval(plan)).isFalse();
    }

    @Test
    void requiresApproval_nullTimeout_treatedAsZeroForDurationCalc() {
        // Two steps: one null timeout (0s) + one 90s. Total = 90s < 300s.
        NavigateStep nullTimeoutStep = new NavigateStep(0, 64, 0, null, RetryPolicy.NONE);
        NavigateStep shortStep = navigate(SHORT);
        Plan plan = planWith(List.of(nullTimeoutStep, shortStep));

        assertThat(gate.requiresApproval(plan)).isFalse();
    }

    // --- Helpers ---

    private static NavigateStep navigate(Duration timeout) {
        return new NavigateStep(0, 64, 0, timeout, RetryPolicy.NONE);
    }

    private static Plan planWith(List<Step> steps) {
        return new Plan(UUID.randomUUID(), "Test plan", "player", Instant.now(),
                steps, TerminationPolicy.DEFAULT);
    }
}
