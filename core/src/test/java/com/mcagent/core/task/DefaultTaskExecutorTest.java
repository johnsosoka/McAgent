package com.mcagent.core.task;

import com.mcagent.core.service.BotOperations;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.RunLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultTaskExecutor}.
 *
 * Uses a fake {@link JobSupervisor} that is controllable from the test so we can
 * verify persistence, state transitions, and rejection without spinning real threads.
 */
class DefaultTaskExecutorTest {

    private SessionStateRepository repository;
    private BotOperations bot;
    private Counters counters;
    private RunLog runLog;
    private FakeJobSupervisor fakeSupervisor;
    private DefaultTaskExecutor executor;

    @BeforeEach
    void setUp() {
        repository = mock(SessionStateRepository.class);
        bot = mock(BotOperations.class);
        counters = new Counters();
        runLog = mock(RunLog.class);
        fakeSupervisor = new FakeJobSupervisor();
        executor = new DefaultTaskExecutor(fakeSupervisor, repository, bot, counters, runLog);
        // Server address must be set before submit() is called (Fix 5).
        executor.setServerAddress("localhost:25565");

        // Default: findById returns empty so persistSnapshot creates a new entity.
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // submit — happy path
    // -------------------------------------------------------------------------

    @Test
    void submit_returnsRunningSnapshot() {
        Plan plan = minimalPlan();

        TaskSnapshot snapshot = executor.submit(plan);

        assertThat(snapshot.state()).isEqualTo(TaskState.RUNNING);
        assertThat(snapshot.id()).isEqualTo(plan.id());
        assertThat(snapshot.plan()).isEqualTo(plan);
        assertThat(snapshot.currentStepIndex()).isEqualTo(0);
    }

    @Test
    void submit_persistsSessionState() {
        Plan plan = minimalPlan();

        executor.submit(plan);

        verify(repository, atLeastOnce()).save(argThat(entity ->
                entity.getTaskId().equals(plan.id()) &&
                entity.getState() == TaskState.RUNNING));
    }

    @Test
    void submit_incrementsSubmittedCounter() {
        executor.submit(minimalPlan());

        assertThat(counters.get("plan.submitted")).isEqualTo(1L);
    }

    @Test
    void submit_handsPlanToSupervisor() {
        Plan plan = minimalPlan();
        executor.submit(plan);

        assertThat(fakeSupervisor.lastSubmitted).isNotNull();
        assertThat(fakeSupervisor.lastSubmitted.id()).isEqualTo(plan.id());
    }

    // -------------------------------------------------------------------------
    // submit — rejection when plan already running
    // -------------------------------------------------------------------------

    @Test
    void submit_rejectsSecondPlanWhileFirstRunning() {
        Plan first = minimalPlan();
        Plan second = minimalPlan();

        executor.submit(first);

        assertThatThrownBy(() -> executor.submit(second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
    }

    // -------------------------------------------------------------------------
    // currentTask / snapshot
    // -------------------------------------------------------------------------

    @Test
    void currentTask_returnsEmpty_whenIdle() {
        assertThat(executor.currentTask()).isEmpty();
    }

    @Test
    void currentTask_returnsSnapshot_whenRunning() {
        Plan plan = minimalPlan();
        executor.submit(plan);

        assertThat(executor.currentTask()).isPresent();
        assertThat(executor.currentTask().get().id()).isEqualTo(plan.id());
    }

    @Test
    void snapshot_returnsById() {
        Plan plan = minimalPlan();
        executor.submit(plan);

        assertThat(executor.snapshot(plan.id())).isPresent();
        assertThat(executor.snapshot(UUID.randomUUID())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------------------

    @Test
    void cancel_returnsFalse_whenNoCurrentTask() {
        boolean result = executor.cancel(UUID.randomUUID());

        assertThat(result).isFalse();
    }

    @Test
    void cancel_returnsTrue_andSignalsSupervisor() {
        Plan plan = minimalPlan();
        executor.submit(plan);

        boolean result = executor.cancel(plan.id());

        assertThat(result).isTrue();
        assertThat(fakeSupervisor.cancelRequested).isTrue();
    }

    @Test
    void cancel_returnsFalse_forWrongId() {
        executor.submit(minimalPlan());

        boolean result = executor.cancel(UUID.randomUUID());

        assertThat(result).isFalse();
        assertThat(fakeSupervisor.cancelRequested).isFalse();
    }

    // -------------------------------------------------------------------------
    // State transition via onUpdate callback
    // -------------------------------------------------------------------------

    @Test
    void onUpdate_withCompletedState_clearsCurrentTask() throws InterruptedException {
        Plan plan = minimalPlan();
        CountDownLatch completed = new CountDownLatch(1);

        executor.submit(plan);

        // Simulate the supervisor calling back with COMPLETED.
        fakeSupervisor.triggerUpdate(new TaskSnapshot(
                plan.id(), plan, TaskState.COMPLETED, 0, "done",
                Instant.now(), Optional.empty()));

        // Give the atomic update a moment to propagate.
        assertThat(executor.currentTask()).isEmpty();
    }

    @Test
    void onUpdate_withFailedState_clearsCurrentTask() {
        Plan plan = minimalPlan();
        executor.submit(plan);

        fakeSupervisor.triggerUpdate(new TaskSnapshot(
                plan.id(), plan, TaskState.FAILED, 0, "",
                Instant.now(), Optional.of("TIMEOUT: too slow")));

        assertThat(executor.currentTask()).isEmpty();
    }

    @Test
    void onUpdate_withCancelledState_clearsCurrentTask() {
        Plan plan = minimalPlan();
        executor.submit(plan);

        fakeSupervisor.triggerUpdate(new TaskSnapshot(
                plan.id(), plan, TaskState.CANCELLED, 0, "",
                Instant.now(), Optional.empty()));

        assertThat(executor.currentTask()).isEmpty();
    }

    @Test
    void onUpdate_afterCompletion_allowsNewPlanSubmit() {
        Plan first = minimalPlan();
        executor.submit(first);

        // Simulate completion
        fakeSupervisor.triggerUpdate(new TaskSnapshot(
                first.id(), first, TaskState.COMPLETED, 0, "done",
                Instant.now(), Optional.empty()));

        // Should succeed — no longer running.
        Plan second = minimalPlan();
        TaskSnapshot result = executor.submit(second);
        assertThat(result.state()).isEqualTo(TaskState.RUNNING);
    }

    // -------------------------------------------------------------------------
    // Fix 5 — serverAddress must be set before submit
    // -------------------------------------------------------------------------

    @Test
    void submit_throwsIllegalState_whenServerAddressNotSet() {
        // Build a fresh executor without calling setServerAddress.
        DefaultTaskExecutor freshExecutor =
                new DefaultTaskExecutor(fakeSupervisor, repository, bot, counters, runLog);

        assertThatThrownBy(() -> freshExecutor.submit(minimalPlan()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("setServerAddress");
    }

    @Test
    void submit_succeedsAfterSetServerAddress() {
        // Verify the happy path from setUp still works (address already set in setUp).
        TaskSnapshot snap = executor.submit(minimalPlan());
        assertThat(snap.state()).isEqualTo(TaskState.RUNNING);
    }

    // -------------------------------------------------------------------------
    // Fix 9 — persistSnapshot swallows exceptions, task continues
    // -------------------------------------------------------------------------

    @Test
    void persistSnapshot_swallowsExceptionsAndContinues() {
        // repository.save() throws; should not propagate out of submit().
        when(repository.save(any())).thenThrow(new RuntimeException("DB unavailable"));

        Plan plan = minimalPlan();
        // Must not throw — persistence failure is non-fatal.
        TaskSnapshot snap = executor.submit(plan);

        // Task is still in memory and running.
        assertThat(snap).isNotNull();
        assertThat(snap.state()).isEqualTo(TaskState.RUNNING);
        // Supervisor still received the job.
        assertThat(fakeSupervisor.lastSubmitted).isNotNull();
        assertThat(fakeSupervisor.lastSubmitted.id()).isEqualTo(plan.id());
        // Submitted counter still increments.
        assertThat(counters.get("plan.submitted")).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Plan minimalPlan() {
        return new Plan(
                UUID.randomUUID(),
                "Test plan",
                "testplayer",
                Instant.now(),
                List.of(new NavigateStep(0, 64, 0, Duration.ofMinutes(1), RetryPolicy.NONE)),
                TerminationPolicy.DEFAULT);
    }

    /**
     * Fake JobSupervisor that captures what was submitted and exposes a
     * hook to fire update callbacks from the test thread.
     */
    static class FakeJobSupervisor extends JobSupervisor {

        TaskSnapshot lastSubmitted;
        boolean cancelRequested = false;
        private java.util.function.Consumer<TaskSnapshot> onUpdate;

        FakeJobSupervisor() {
            super(null, null, null, null, null);
        }

        @Override
        public void submit(TaskSnapshot initial, BotOperations bot,
                           java.util.function.Consumer<TaskSnapshot> onUpdate) {
            this.lastSubmitted = initial;
            this.onUpdate = onUpdate;
        }

        @Override
        void requestCancel() {
            cancelRequested = true;
        }

        void triggerUpdate(TaskSnapshot snap) {
            if (onUpdate != null) {
                onUpdate.accept(snap);
            }
        }

        // Override lifecycle methods to do nothing in tests.
        @Override
        public void start() {}

        @Override
        public void stop() {}
    }
}
