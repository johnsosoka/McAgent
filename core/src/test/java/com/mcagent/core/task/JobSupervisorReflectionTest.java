package com.mcagent.core.task;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.memory.episodic.AgentEvent;
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
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.timeout;

/**
 * Tests for the Reflexion-pattern end-of-task reflection in {@link JobSupervisor}.
 *
 * <p>Verifies that {@link ReflectionService#reflect(String)} is called after a plan
 * reaches a terminal state, and that the resulting text is persisted via
 * {@link EpisodicMemoryService#writeWithImportance} with the correct importance
 * score (7 for COMPLETED, 8 for FAILED). Also verifies that CANCELLED plans
 * do not trigger a reflection call.</p>
 *
 * <p>All tests carry a 10-second timeout to guard against supervisor deadlocks.</p>
 */
@Timeout(10)
class JobSupervisorReflectionTest {

    private static final String REFLECTION_TEXT =
            "Navigation succeeded on the first attempt. No steps failed. " +
            "Reduce search radius next time to improve speed.";

    private LangChain4jService langChainService;
    private EpisodicMemoryService episodicMemoryService;
    private ReflectionService reflectionService;
    private Counters counters;
    private BotOperations bot;
    private JobSupervisor supervisor;

    @BeforeEach
    void setUp() {
        langChainService = mock(LangChain4jService.class);
        episodicMemoryService = mock(EpisodicMemoryService.class);
        reflectionService = mock(ReflectionService.class);
        counters = new Counters();
        bot = mock(BotOperations.class);

        when(langChainService.processFailureEscalation(any(TaskFailure.class)))
                .thenReturn(new FailureDecision("abandon for test", FailureDecision.ABANDON, null));
        when(reflectionService.reflect(anyString())).thenReturn(REFLECTION_TEXT);

        SessionContext sessionContext = new SessionContext();
        sessionContext.setSessionId("reflection-test-session");

        supervisor = new JobSupervisor(
                langChainService,
                mock(SessionStateRepository.class),
                new JobTracker(),
                counters,
                mock(RunLog.class),
                new BotProperties(),
                episodicMemoryService,
                sessionContext,
                reflectionService);
        supervisor.start();
    }

    @AfterEach
    void tearDown() {
        supervisor.stop();
    }

    // -------------------------------------------------------------------------
    // COMPLETED — reflection written with importance=7
    // -------------------------------------------------------------------------

    /**
     * When a plan completes successfully, the supervisor must:
     * 1. Call {@link ReflectionService#reflect} with context containing the plan summary.
     * 2. Persist the result via {@link EpisodicMemoryService#writeWithImportance}
     *    with {@code type=reflection} and {@code importance=7}.
     */
    @Test
    void completedPlan_writesReflectionWithImportance7() throws InterruptedException {
        BotOperations.Location target = new BotOperations.Location(10, 64, 10);
        when(bot.navigateTo(10, 64, 10))
                .thenReturn(PathResult.builder().success(true).message("ok").build());
        when(bot.getCurrentPosition()).thenReturn(target);

        CountDownLatch done = new CountDownLatch(1);
        supervisor.submit(snapshotFor("navigate to target", new NavigateStep(10, 64, 10, Duration.ofSeconds(30), RetryPolicy.NONE)), bot,
                snap -> {
                    if (snap.state() == TaskState.COMPLETED || snap.state() == TaskState.FAILED) {
                        done.countDown();
                    }
                });

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();

        // Wait for the reflection to complete on the supervisor thread (it runs after onUpdate).
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(reflectionService, timeout(3000).times(1)).reflect(contextCaptor.capture());

        String passedContext = contextCaptor.getValue();
        assertThat(passedContext).contains("navigate to target");
        assertThat(passedContext).contains("SUCCESS");

        // Persisted with type=reflection and importance=7.
        verify(episodicMemoryService, timeout(3000).times(1))
                .writeWithImportance(
                        eq(AgentEvent.TYPE_REFLECTION),
                        eq(REFLECTION_TEXT),
                        anyString(),
                        any(Optional.class),
                        eq(7));
    }

    /**
     * The "reflection.success" counter is incremented exactly once after a
     * completed plan.
     *
     * <p>Uses {@code timeout} verify to wait for the reflection async call to complete
     * on the supervisor thread after the plan-complete callback fires.</p>
     */
    @Test
    void completedPlan_incrementsSuccessCounter() throws InterruptedException {
        BotOperations.Location target = new BotOperations.Location(5, 64, 5);
        when(bot.navigateTo(5, 64, 5))
                .thenReturn(PathResult.builder().success(true).message("ok").build());
        when(bot.getCurrentPosition()).thenReturn(target);

        CountDownLatch done = new CountDownLatch(1);
        supervisor.submit(snapshotFor("reach waypoint", new NavigateStep(5, 64, 5, Duration.ofSeconds(30), RetryPolicy.NONE)), bot,
                snap -> {
                    if (snap.state() == TaskState.COMPLETED || snap.state() == TaskState.FAILED) {
                        done.countDown();
                    }
                });

        done.await(8, TimeUnit.SECONDS);

        // Wait for the async reflection write to complete on the supervisor thread.
        verify(episodicMemoryService, timeout(3000).times(1))
                .writeWithImportance(anyString(), anyString(), anyString(), any(Optional.class), eq(7));

        assertThat(counters.get("reflection.success")).isEqualTo(1L);
        assertThat(counters.get("reflection.failure")).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // FAILED — reflection written with importance=8
    // -------------------------------------------------------------------------

    /**
     * When a plan fails (step failure escalates to ABANDON), the supervisor must
     * call {@link ReflectionService#reflect} and persist with {@code importance=8}.
     */
    @Test
    void failedPlan_writesReflectionWithImportance8() throws InterruptedException {
        // navigateTo fails immediately — escalation will ABANDON.
        when(bot.navigateTo(99, 64, 99))
                .thenReturn(PathResult.builder().success(false).message("blocked").build());
        when(bot.getCurrentPosition()).thenReturn(new BotOperations.Location(0, 64, 0));

        CountDownLatch done = new CountDownLatch(1);
        supervisor.submit(snapshotFor("navigate to unreachable", new NavigateStep(99, 64, 99, Duration.ofMillis(200), RetryPolicy.NONE)), bot,
                snap -> {
                    if (snap.state() == TaskState.FAILED || snap.state() == TaskState.COMPLETED) {
                        done.countDown();
                    }
                });

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();

        // Wait for the async reflection write to complete on the supervisor thread.
        verify(reflectionService, timeout(3000).times(1)).reflect(anyString());

        verify(episodicMemoryService, timeout(3000).times(1))
                .writeWithImportance(
                        eq(AgentEvent.TYPE_REFLECTION),
                        eq(REFLECTION_TEXT),
                        anyString(),
                        any(Optional.class),
                        eq(8));
    }

    /**
     * The "reflection.failure" counter is incremented exactly once after a failed plan.
     *
     * <p>Uses {@code timeout} verify to wait for the reflection async call to complete
     * on the supervisor thread after the plan-failed callback fires.</p>
     */
    @Test
    void failedPlan_incrementsFailureCounter() throws InterruptedException {
        when(bot.navigateTo(99, 64, 99))
                .thenReturn(PathResult.builder().success(false).message("blocked").build());
        when(bot.getCurrentPosition()).thenReturn(new BotOperations.Location(0, 64, 0));

        CountDownLatch done = new CountDownLatch(1);
        supervisor.submit(snapshotFor("fail task", new NavigateStep(99, 64, 99, Duration.ofMillis(200), RetryPolicy.NONE)), bot,
                snap -> {
                    if (snap.state() == TaskState.FAILED || snap.state() == TaskState.COMPLETED) {
                        done.countDown();
                    }
                });

        done.await(8, TimeUnit.SECONDS);

        // Wait for the async reflection write to complete on the supervisor thread.
        verify(episodicMemoryService, timeout(3000).times(1))
                .writeWithImportance(anyString(), anyString(), anyString(), any(Optional.class), eq(8));

        assertThat(counters.get("reflection.failure")).isEqualTo(1L);
        assertThat(counters.get("reflection.success")).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // CANCELLED — no reflection
    // -------------------------------------------------------------------------

    /**
     * A CANCELLED plan must not trigger a reflection call. Cancellation is
     * a player interrupt, not a task outcome worth learning from.
     */
    @Test
    void cancelledPlan_doesNotWriteReflection() throws InterruptedException {
        BotOperations.Location far = new BotOperations.Location(0, 64, 0);
        when(bot.navigateTo(100, 64, 100))
                .thenReturn(PathResult.builder().success(true).message("ok").build());
        when(bot.getCurrentPosition()).thenReturn(far); // never arrives

        CountDownLatch startedPolling = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        supervisor.submit(snapshotFor("long navigation", new NavigateStep(100, 64, 100, Duration.ofMinutes(5), RetryPolicy.NONE)), bot,
                snap -> {
                    updates.add(snap);
                    if (snap.state() == TaskState.RUNNING) {
                        startedPolling.countDown();
                    }
                    if (snap.state() == TaskState.CANCELLED || snap.state() == TaskState.FAILED
                            || snap.state() == TaskState.COMPLETED) {
                        done.countDown();
                    }
                });

        startedPolling.await(5, TimeUnit.SECONDS);
        supervisor.requestCancel();

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.CANCELLED);

        // No reflection on cancellation.
        verify(reflectionService, never()).reflect(anyString());
        verify(episodicMemoryService, never()).writeWithImportance(
                anyString(), anyString(), anyString(), any(Optional.class), anyInt());
    }

    // -------------------------------------------------------------------------
    // Reflection failure is swallowed
    // -------------------------------------------------------------------------

    /**
     * If the ReflectionService throws an exception, the task lifecycle must not
     * be disrupted — the plan still lands in COMPLETED state.
     */
    @Test
    void reflectionFailure_doesNotBreakTaskLifecycle() throws InterruptedException {
        when(reflectionService.reflect(anyString()))
                .thenThrow(new RuntimeException("simulated LLM timeout"));

        BotOperations.Location target = new BotOperations.Location(3, 64, 3);
        when(bot.navigateTo(3, 64, 3))
                .thenReturn(PathResult.builder().success(true).message("ok").build());
        when(bot.getCurrentPosition()).thenReturn(target);

        CountDownLatch done = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        supervisor.submit(snapshotFor("navigate short", new NavigateStep(3, 64, 3, Duration.ofSeconds(30), RetryPolicy.NONE)), bot,
                snap -> {
                    updates.add(snap);
                    if (snap.state() == TaskState.COMPLETED || snap.state() == TaskState.FAILED) {
                        done.countDown();
                    }
                });

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.COMPLETED);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private TaskSnapshot snapshotFor(String summary, Step step) {
        Plan plan = new Plan(
                UUID.randomUUID(),
                summary,
                "testplayer",
                Instant.now(),
                List.of(step),
                new TerminationPolicy(Duration.ofHours(1), false));
        return new TaskSnapshot(
                plan.id(), plan, TaskState.RUNNING, 0, "starting",
                Instant.now(), Optional.empty());
    }
}
