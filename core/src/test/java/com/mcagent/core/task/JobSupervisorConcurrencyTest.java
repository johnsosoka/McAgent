package com.mcagent.core.task;

import com.mcagent.core.model.PathResult;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concurrency and edge-case tests for {@link JobSupervisor} and {@link DefaultTaskExecutor}.
 *
 * <p>These tests are additive to {@link JobSupervisorTest} (which covers the happy paths)
 * and focus on the harder scenarios called out in Q8 of Sprint 13:</p>
 * <ul>
 *   <li>Q8-9: cancel during a long WaitForStep — must terminate cleanly.</li>
 *   <li>Q8-10: second submit rejected while first is running.</li>
 *   <li>Q8-11: unsupported step type yields a clear error.</li>
 * </ul>
 *
 * <p>All tests carry a 10-second timeout to prevent CI hangs if the supervisor
 * ever blocks on a condition that is never satisfied.</p>
 */
@Timeout(10)
class JobSupervisorConcurrencyTest {

    private LangChain4jService langChainService;
    private SessionStateRepository repository;
    private JobTracker jobTracker;
    private Counters counters;
    private RunLog runLog;
    private BotOperations bot;
    private JobSupervisor supervisor;

    @BeforeEach
    void setUp() {
        langChainService = mock(LangChain4jService.class);
        repository = mock(SessionStateRepository.class);
        jobTracker = new JobTracker();
        counters = new Counters();
        runLog = mock(RunLog.class);
        bot = mock(BotOperations.class);

        when(repository.findById(org.mockito.ArgumentMatchers.<UUID>any())).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.<SessionState>any()))
                .thenAnswer(inv -> inv.getArgument(0));

        supervisor = new JobSupervisor(langChainService, repository, jobTracker, counters, runLog);
        supervisor.start();
    }

    @AfterEach
    void tearDown() {
        supervisor.stop();
    }

    // =========================================================================
    // Q8-9: submit then cancel — WaitForStep with a 1-minute timeout
    // =========================================================================

    /**
     * Submit a Plan whose single step is a {@link WaitForStep} with a 1-minute
     * timeout (condition never satisfied). Cancel after 200 ms. The supervisor must
     * transition to CANCELLED and not remain blocked waiting.
     */
    @Test
    void submit_thenCancel_terminatesCleanly() throws InterruptedException {
        // Condition is never satisfied — bot always reports no iron.
        when(bot.hasItem(anyString(), anyInt())).thenReturn(false);

        WaitCondition neverTrue = new InventoryHasCondition("minecraft:diamond", 99);
        WaitForStep longWait = new WaitForStep(neverTrue, Duration.ofMinutes(1), RetryPolicy.NONE);

        CountDownLatch startedPolling = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        supervisor.submit(snapshotFor(longWait), bot, snap -> {
            updates.add(snap);
            if (snap.state() == TaskState.RUNNING) {
                startedPolling.countDown();
            }
            if (snap.state() == TaskState.CANCELLED
                    || snap.state() == TaskState.FAILED
                    || snap.state() == TaskState.COMPLETED) {
                terminal.countDown();
            }
        });

        // Wait for the supervisor to enter the polling loop, then cancel.
        assertThat(startedPolling.await(3, TimeUnit.SECONDS))
                .as("Supervisor should start polling within 3s")
                .isTrue();

        supervisor.requestCancel();

        // Must resolve to CANCELLED well within the 1-minute step timeout.
        assertThat(terminal.await(5, TimeUnit.SECONDS))
                .as("Supervisor must honour cancel within 5s (not wait the full 1-min timeout)")
                .isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.CANCELLED);
    }

    // =========================================================================
    // Q8-10: second submit while first is running → rejected
    // =========================================================================

    /**
     * {@link DefaultTaskExecutor} enforces one-at-a-time semantics via CAS.
     * Submitting a second plan while one is RUNNING must throw
     * {@link IllegalStateException} with a message that names the conflicting task.
     *
     * <p>This verifies the contract the C1 agent chose (per {@link DefaultTaskExecutor}
     * code review): same-snapshot-or-exception, not silent queue.</p>
     */
    @Test
    void submit_thenSubmit_secondRejected() {
        // Build a DefaultTaskExecutor with a fake supervisor that never completes.
        SessionStateRepository repo = mock(SessionStateRepository.class);
        when(repo.findById(org.mockito.ArgumentMatchers.<UUID>any())).thenReturn(Optional.empty());
        when(repo.save(org.mockito.ArgumentMatchers.<SessionState>any()))
                .thenAnswer(inv -> inv.getArgument(0));

        Counters c = new Counters();
        RunLog rl = mock(RunLog.class);
        BotOperations mockBot = mock(BotOperations.class);

        // FakeJobSupervisor that never fires onUpdate — plan stays RUNNING indefinitely.
        DefaultTaskExecutorTest.FakeJobSupervisor fakeSupervisor =
                new DefaultTaskExecutorTest.FakeJobSupervisor();
        DefaultTaskExecutor executor =
                new DefaultTaskExecutor(fakeSupervisor, repo, mockBot, c, rl);
        executor.setServerAddress("localhost:25565");

        Plan first = minimalPlan("Plan A");
        Plan second = minimalPlan("Plan B");

        // First submit succeeds.
        TaskSnapshot snap = executor.submit(first);
        assertThat(snap.state()).isEqualTo(TaskState.RUNNING);

        // Second submit must be rejected immediately.
        assertThatThrownBy(() -> executor.submit(second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
    }

    // =========================================================================
    // Q8-11: unsupported step type → clear error from PlanSerializer
    // =========================================================================

    /**
     * Sprint 13 ships Navigate, Mine, and WaitFor only.
     * A JSON step array with an unknown type (e.g. "SubPlan") must fail at
     * parse time with a clear {@link IllegalArgumentException} rather than an NPE,
     * ClassCastException, or silent no-op.
     *
     * <p>This validates the Jackson sealed-type mapping in {@link PlanSerializer} —
     * the production guard that prevents unsupported step types from reaching
     * {@link JobSupervisor}.</p>
     */
    @Test
    void nestedPlan_notSupported_returnsClearError() {
        // JSON using an unknown step type that does not match any @JsonSubTypes entry.
        String unsupportedStepJson = """
                [{"type":"SubPlan","subPlanId":"some-uuid","timeout":"PT5M","retry":{"maxRetries":0,"backoff":"PT0S"}}]
                """;

        assertThatThrownBy(() -> PlanSerializer.stepsFromJson(unsupportedStepJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid steps JSON");
    }

    /**
     * A "Craft" step type (Sprint 14 deferred work) must also fail cleanly.
     */
    @Test
    void craftStep_notSupported_returnsClearError() {
        String craftStepJson = """
                [{"type":"Craft","recipe":"minecraft:furnace","quantity":1,"timeout":"PT5M","retry":{"maxRetries":0,"backoff":"PT0S"}}]
                """;

        assertThatThrownBy(() -> PlanSerializer.stepsFromJson(craftStepJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid steps JSON");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private TaskSnapshot snapshotFor(Step step) {
        Plan plan = new Plan(
                UUID.randomUUID(),
                "Concurrency test plan",
                "testplayer",
                Instant.now(),
                List.of(step),
                new TerminationPolicy(Duration.ofHours(1), false));
        return new TaskSnapshot(
                plan.id(), plan, TaskState.RUNNING, 0, "starting",
                Instant.now(), Optional.empty());
    }

    private Plan minimalPlan(String summary) {
        return new Plan(
                UUID.randomUUID(),
                summary,
                "testplayer",
                Instant.now(),
                List.of(new NavigateStep(0, 64, 0, Duration.ofMinutes(1), RetryPolicy.NONE)),
                TerminationPolicy.DEFAULT);
    }
}
