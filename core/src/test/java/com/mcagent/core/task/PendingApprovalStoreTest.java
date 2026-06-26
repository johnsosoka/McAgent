package com.mcagent.core.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PendingApprovalStore}.
 *
 * <p>Verifies the four core operations: set, peek, getAndClear, and clear.
 * Each test exercises a specific semantic boundary to catch regression
 * if the internal {@code AtomicReference} mechanics are changed.</p>
 */
class PendingApprovalStoreTest {

    private PendingApprovalStore store;
    private Plan planA;
    private Plan planB;

    @BeforeEach
    void setUp() {
        store = new PendingApprovalStore();
        planA = makePlan("Navigate to base");
        planB = makePlan("Mine iron ore");
    }

    // --- Initial state ---

    @Test
    void peek_returnsEmpty_whenNothingStored() {
        assertThat(store.peek()).isEmpty();
    }

    @Test
    void getAndClear_returnsEmpty_whenNothingStored() {
        assertThat(store.getAndClear()).isEmpty();
    }

    // --- set + peek ---

    @Test
    void peek_returnsPlan_afterSet() {
        store.set(planA);

        Optional<Plan> result = store.peek();

        assertThat(result).isPresent();
        assertThat(result.get().summary()).isEqualTo("Navigate to base");
    }

    @Test
    void peek_doesNotRemovePlan() {
        store.set(planA);

        store.peek();
        Optional<Plan> secondPeek = store.peek();

        assertThat(secondPeek).isPresent();
    }

    // --- set replaces existing ---

    @Test
    void set_replacesExistingPlan_whenCalledTwice() {
        store.set(planA);
        store.set(planB);

        Optional<Plan> result = store.peek();

        assertThat(result).isPresent();
        assertThat(result.get().summary()).isEqualTo("Mine iron ore");
    }

    // --- getAndClear ---

    @Test
    void getAndClear_returnsPlan_andThenStoreIsEmpty() {
        store.set(planA);

        Optional<Plan> first = store.getAndClear();
        Optional<Plan> second = store.getAndClear();

        assertThat(first).isPresent();
        assertThat(first.get().summary()).isEqualTo("Navigate to base");
        assertThat(second).isEmpty();
    }

    @Test
    void getAndClear_isAtomicConsumeOperation() {
        store.set(planA);

        // The first call gets the plan; the second must return empty.
        store.getAndClear();
        assertThat(store.peek()).isEmpty();
    }

    // --- clear ---

    @Test
    void clear_removesStoredPlan() {
        store.set(planA);
        store.clear();

        assertThat(store.peek()).isEmpty();
    }

    @Test
    void clear_isNoOp_whenAlreadyEmpty() {
        // Should not throw
        store.clear();

        assertThat(store.peek()).isEmpty();
    }

    // --- Helpers ---

    private static Plan makePlan(String summary) {
        return new Plan(
                UUID.randomUUID(),
                summary,
                "player",
                Instant.now(),
                List.of(new NavigateStep(0, 64, 0, Duration.ofMinutes(5), RetryPolicy.NONE)),
                TerminationPolicy.DEFAULT);
    }
}
