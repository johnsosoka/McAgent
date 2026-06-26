package com.mcagent.core.task;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.memory.episodic.EpisodicMemoryService;
import com.mcagent.core.memory.episodic.SessionContext;
import com.mcagent.core.model.PathResult;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.FailureDecision;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.service.ReflectionService;
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

        EpisodicMemoryService noOpEpisodic = new EpisodicMemoryService() {
            @Override
            public void write(String type, String content, String sessionId,
                              java.util.Optional<String> relatedTaskId) {}
            @Override
            public void writeWithImportance(String type, String content, String sessionId,
                                            java.util.Optional<String> relatedTaskId, int importance) {}
            @Override
            public java.util.List<com.mcagent.core.memory.episodic.AgentEvent> retrieve(
                    String queryContext, String sessionId, int topK) {
                return java.util.List.of();
            }
        };
        SessionContext sessionContext = new SessionContext();
        sessionContext.setSessionId("test-session");

        ReflectionService noOpReflection = mock(ReflectionService.class);
        when(noOpReflection.reflect(anyString())).thenReturn("Test reflection.");

        supervisor = new JobSupervisor(langChainService, repository, jobTracker, counters, runLog,
                new BotProperties(), noOpEpisodic, sessionContext, noOpReflection);
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

    // =========================================================================
    // Q1: explain-then-replan escalation tests
    // =========================================================================

    /**
     * When a step fails and the explainer returns ABANDON, the supervisor transitions
     * the task to FAILED and does not continue executing further steps.
     */
    @Test
    void stepFailure_escalationAbandon_transitionsToFailed() throws InterruptedException {
        // Navigate step always fails — bot never starts successfully.
        when(bot.navigateTo(99, 64, 99))
                .thenReturn(PathResult.builder().success(false).message("Baritone error").build());
        // Position stub for escalation world-state snapshot.
        when(bot.getCurrentPosition()).thenReturn(new BotOperations.Location(0, 64, 0));

        // Explainer always returns ABANDON.
        FailureDecision abandon = new FailureDecision("Cannot reach destination.", FailureDecision.ABANDON, null);
        when(langChainService.processFailureEscalation(org.mockito.ArgumentMatchers.any()))
                .thenReturn(abandon);

        CountDownLatch terminal = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        NavigateStep failingStep = new NavigateStep(99, 64, 99, Duration.ofMillis(200), RetryPolicy.NONE);
        supervisor.submit(snapshotFor(failingStep), bot, snap -> {
            updates.add(snap);
            if (snap.state() == TaskState.FAILED || snap.state() == TaskState.COMPLETED
                    || snap.state() == TaskState.CANCELLED) {
                terminal.countDown();
            }
        });

        assertThat(terminal.await(8, TimeUnit.SECONDS)).isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.FAILED);
        assertThat(last.lastFailureReason().orElse("")).contains("Cannot reach destination");
    }

    /**
     * When a step fails and the explainer returns RETRY, the supervisor retries that
     * step. On the second attempt (after retry), the step succeeds and the plan completes.
     */
    @Test
    void stepFailure_escalationRetry_retiesStepAndSucceeds() throws InterruptedException {
        BotOperations.Location far    = new BotOperations.Location(0, 64, 0);
        BotOperations.Location target = new BotOperations.Location(10, 64, 10);

        // First navigate attempt fails; second succeeds.
        when(bot.navigateTo(10, 64, 10))
                .thenReturn(PathResult.builder().success(false).message("timeout").build())  // first attempt
                .thenReturn(PathResult.builder().success(true).message("ok").build());        // after retry
        when(bot.getCurrentPosition())
                .thenReturn(far)      // first poll (first attempt)
                .thenReturn(target);  // first poll (retry attempt) — arrived

        FailureDecision retry = new FailureDecision("Transient timeout.", FailureDecision.RETRY, null);
        when(langChainService.processFailureEscalation(org.mockito.ArgumentMatchers.any()))
                .thenReturn(retry);

        CountDownLatch terminal = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        NavigateStep step = new NavigateStep(10, 64, 10, Duration.ofMillis(200), RetryPolicy.NONE);
        supervisor.submit(snapshotFor(step), bot, snap -> {
            updates.add(snap);
            if (snap.state() == TaskState.COMPLETED || snap.state() == TaskState.FAILED
                    || snap.state() == TaskState.CANCELLED) {
                terminal.countDown();
            }
        });

        assertThat(terminal.await(8, TimeUnit.SECONDS)).isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.COMPLETED);
    }

    /**
     * When a step fails and the explainer returns REVISE, the supervisor replaces the
     * remaining steps with the revised plan and continues execution. The revised plan
     * here is a single WaitForStep whose condition is immediately satisfied.
     */
    @Test
    void stepFailure_escalationRevise_executesRevisedPlan() throws InterruptedException {
        // Original step always fails.
        when(bot.navigateTo(99, 64, 99))
                .thenReturn(PathResult.builder().success(false).message("Baritone error").build());
        when(bot.getCurrentPosition()).thenReturn(new BotOperations.Location(0, 64, 0));

        // Revised plan: WaitFor with a condition that is immediately true.
        when(bot.hasItem("minecraft:iron_ingot", 1)).thenReturn(true);

        java.util.Map<String, Object> revisedPlan = new java.util.HashMap<>();
        revisedPlan.put("summary", "Revised: wait for iron");
        revisedPlan.put("steps", List.of(
                java.util.Map.of("type", "WaitFor",
                        "condition", java.util.Map.of("type", "InventoryHas", "itemId", "minecraft:iron_ingot", "count", 1),
                        "timeoutSeconds", 30)));

        FailureDecision revise = new FailureDecision("Wrong coords — revising.", FailureDecision.REVISE, revisedPlan);
        when(langChainService.processFailureEscalation(org.mockito.ArgumentMatchers.any()))
                .thenReturn(revise);

        CountDownLatch terminal = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        NavigateStep failingStep = new NavigateStep(99, 64, 99, Duration.ofMillis(200), RetryPolicy.NONE);
        supervisor.submit(snapshotFor(failingStep), bot, snap -> {
            updates.add(snap);
            if (snap.state() == TaskState.COMPLETED || snap.state() == TaskState.FAILED
                    || snap.state() == TaskState.CANCELLED) {
                terminal.countDown();
            }
        });

        assertThat(terminal.await(8, TimeUnit.SECONDS)).isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.COMPLETED);
    }

    /**
     * When escalation cap is reached (default 2), the third failure must force ABANDON
     * without calling {@link LangChain4jService#processFailureEscalation} a third time.
     *
     * <p>The cap is set to 1 via a custom {@link BotProperties} so the test stays fast
     * without relying on the default cap of 2.</p>
     */
    @Test
    void escalationCap_forcesAbandon_withoutAdditionalLlmCall() throws InterruptedException {
        // Configure a cap of 1 escalation — easier to trigger in tests.
        BotProperties props = new BotProperties();
        props.getPlan().setMaxEscalationsPerPlan(1);

        supervisor.stop();
        EpisodicMemoryService noOpEpisodic2 = new EpisodicMemoryService() {
            @Override
            public void write(String type, String content, String sessionId,
                              java.util.Optional<String> relatedTaskId) {}
            @Override
            public void writeWithImportance(String type, String content, String sessionId,
                                            java.util.Optional<String> relatedTaskId, int importance) {}
            @Override
            public java.util.List<com.mcagent.core.memory.episodic.AgentEvent> retrieve(
                    String queryContext, String sessionId, int topK) {
                return java.util.List.of();
            }
        };
        SessionContext sessionContext2 = new SessionContext();
        sessionContext2.setSessionId("test-session");
        ReflectionService noOpReflection2 = mock(ReflectionService.class);
        when(noOpReflection2.reflect(anyString())).thenReturn("Test reflection.");
        supervisor = new JobSupervisor(langChainService, repository, jobTracker, counters, runLog,
                props, noOpEpisodic2, sessionContext2, noOpReflection2);
        supervisor.start();

        // Step always fails immediately (navigateTo returns failure).
        when(bot.navigateTo(99, 64, 99))
                .thenReturn(PathResult.builder().success(false).message("blocked").build());
        when(bot.getCurrentPosition()).thenReturn(new BotOperations.Location(0, 64, 0));

        // First escalation returns RETRY — the step will fail again.
        // The cap (1) means the second failure should ABANDON without an LLM call.
        FailureDecision retry = new FailureDecision("Retry it.", FailureDecision.RETRY, null);
        when(langChainService.processFailureEscalation(org.mockito.ArgumentMatchers.any()))
                .thenReturn(retry);

        CountDownLatch terminal = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        NavigateStep step = new NavigateStep(99, 64, 99, Duration.ofMillis(100), RetryPolicy.NONE);
        supervisor.submit(snapshotFor(step), bot, snap -> {
            updates.add(snap);
            if (snap.state() == TaskState.FAILED || snap.state() == TaskState.COMPLETED
                    || snap.state() == TaskState.CANCELLED) {
                terminal.countDown();
            }
        });

        assertThat(terminal.await(8, TimeUnit.SECONDS)).isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.FAILED);

        // Explainer was called exactly once (cap=1 means cap is hit on the 2nd failure).
        org.mockito.Mockito.verify(langChainService, org.mockito.Mockito.times(1))
                .processFailureEscalation(org.mockito.ArgumentMatchers.any());
    }
}
