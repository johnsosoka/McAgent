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
import com.mcagent.core.task.TaskFailure;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import static org.mockito.Mockito.*;

/**
 * Tests for {@link JobSupervisor} step execution logic using a fake {@link BotOperations}.
 *
 * All tests have a 10-second timeout to prevent hangs if the supervisor gets stuck.
 */
@Timeout(10)
class JobSupervisorTest {

    private LangChain4jService langChainService;
    private SessionStateRepository repository;
    private JobTracker jobTracker;
    private Counters counters;
    private RunLog runLog;
    private BotOperations bot;
    private ReflectionService reflectionService;
    private JobSupervisor supervisor;

    @BeforeEach
    void setUp() {
        langChainService = mock(LangChain4jService.class);
        repository = mock(SessionStateRepository.class);
        jobTracker = new JobTracker();
        counters = new Counters();
        runLog = mock(RunLog.class);
        bot = mock(BotOperations.class);
        reflectionService = mock(ReflectionService.class);
        when(reflectionService.reflect(anyString())).thenReturn("Test reflection text.");

        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Default escalation response: ABANDON — keeps existing tests that expect FAILED state working.
        when(langChainService.processFailureEscalation(any(TaskFailure.class)))
                .thenReturn(new FailureDecision("Test default: abandon.", FailureDecision.ABANDON, null));

        EpisodicMemoryService noOpEpisodic = new EpisodicMemoryService() {
            @Override
            public void write(String type, String content, String sessionId,
                              java.util.Optional<String> relatedTaskId) {}
            @Override
            public void writeWithImportance(String type, String content, String sessionId,
                                            java.util.Optional<String> relatedTaskId, int importance) {}
            @Override
            public java.util.List<AgentEvent> retrieve(String queryContext, String sessionId, int topK) {
                return java.util.List.of();
            }
        };
        SessionContext sessionContext = new SessionContext();
        sessionContext.setSessionId("test-session");

        supervisor = new JobSupervisor(langChainService, repository, jobTracker, counters, runLog,
                new BotProperties(), noOpEpisodic, sessionContext, reflectionService);
        supervisor.start();
    }

    @AfterEach
    void tearDown() {
        supervisor.stop();
    }

    // -------------------------------------------------------------------------
    // NavigateStep — success path
    // -------------------------------------------------------------------------

    @Test
    void navigateStep_completesWhenBotArrives() throws InterruptedException {
        // Bot starts far away then "arrives" after one poll.
        BotOperations.Location start  = new BotOperations.Location(0, 64, 0);
        BotOperations.Location target = new BotOperations.Location(100, 64, 0);

        when(bot.navigateTo(100, 64, 0)).thenReturn(PathResult.builder().success(true).message("ok").build());
        when(bot.getCurrentPosition())
                .thenReturn(start)         // first poll — far away
                .thenReturn(target);       // second poll — arrived

        CountDownLatch done = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        supervisor.submit(snapshotFor(new NavigateStep(100, 64, 0, Duration.ofSeconds(30), RetryPolicy.NONE)), bot,
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
    // NavigateStep — timeout path
    // -------------------------------------------------------------------------

    @Test
    void navigateStep_failsOnTimeout() throws InterruptedException {
        BotOperations.Location far = new BotOperations.Location(0, 64, 0);

        when(bot.navigateTo(100, 64, 0)).thenReturn(PathResult.builder().success(true).message("ok").build());
        when(bot.getCurrentPosition()).thenReturn(far); // never arrives

        CountDownLatch done = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        // Very short timeout so the test stays fast.
        supervisor.submit(snapshotFor(new NavigateStep(100, 64, 0, Duration.ofMillis(600), RetryPolicy.NONE)), bot,
                snap -> {
                    updates.add(snap);
                    if (snap.state() == TaskState.FAILED || snap.state() == TaskState.COMPLETED) {
                        done.countDown();
                    }
                });

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.FAILED);
        assertThat(last.lastFailureReason().orElse("")).contains("TIMEOUT");
    }

    // -------------------------------------------------------------------------
    // MineStep — success path
    // -------------------------------------------------------------------------

    @Test
    void mineStep_completesWhenQuantityReached() throws InterruptedException {
        when(bot.mine("minecraft:iron_ore", 8, 32))
                .thenReturn(PathResult.builder().success(true).message("Mining").build());
        when(bot.countItem("minecraft:iron_ore"))
                .thenReturn(0)   // before start
                .thenReturn(4)   // first poll
                .thenReturn(8);  // second poll — target reached
        when(bot.getInventorySummary()).thenReturn("some items");

        CountDownLatch done = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        supervisor.submit(snapshotFor(new MineStep("minecraft:iron_ore", 8, 32, Duration.ofSeconds(30), RetryPolicy.NONE)), bot,
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
    // WaitForStep — success path
    // -------------------------------------------------------------------------

    @Test
    void waitForStep_completesWhenConditionMet() throws InterruptedException {
        when(bot.hasItem("minecraft:iron_ore", 4))
                .thenReturn(false)  // first poll
                .thenReturn(true);  // second poll — condition satisfied

        CountDownLatch done = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        WaitCondition cond = new InventoryHasCondition("minecraft:iron_ore", 4);
        supervisor.submit(snapshotFor(new WaitForStep(cond, Duration.ofSeconds(30), RetryPolicy.NONE)), bot,
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
    // Cancel during execution
    // -------------------------------------------------------------------------

    @Test
    void cancel_haltsExecutionBeforeCompletion() throws InterruptedException {
        BotOperations.Location far = new BotOperations.Location(0, 64, 0);

        when(bot.navigateTo(100, 64, 0)).thenReturn(PathResult.builder().success(true).message("ok").build());
        when(bot.getCurrentPosition()).thenReturn(far); // never arrives

        CountDownLatch startedPolling = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        List<TaskSnapshot> updates = new CopyOnWriteArrayList<>();

        supervisor.submit(snapshotFor(new NavigateStep(100, 64, 0, Duration.ofMinutes(5), RetryPolicy.NONE)), bot,
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

        // Wait until the supervisor is actively polling, then cancel.
        startedPolling.await(5, TimeUnit.SECONDS);
        supervisor.requestCancel();

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();

        TaskSnapshot last = updates.get(updates.size() - 1);
        assertThat(last.state()).isEqualTo(TaskState.CANCELLED);
    }

    // -------------------------------------------------------------------------
    // Framework messages are emitted
    // -------------------------------------------------------------------------

    @Test
    void navigateStep_emitsFrameworkMessages() throws InterruptedException {
        BotOperations.Location target = new BotOperations.Location(10, 64, 10);

        when(bot.navigateTo(10, 64, 10)).thenReturn(PathResult.builder().success(true).message("ok").build());
        when(bot.getCurrentPosition()).thenReturn(target); // immediately at destination

        CountDownLatch done = new CountDownLatch(1);

        supervisor.submit(snapshotFor(new NavigateStep(10, 64, 10, Duration.ofSeconds(30), RetryPolicy.NONE)), bot,
                snap -> {
                    if (snap.state() == TaskState.COMPLETED) {
                        done.countDown();
                    }
                });

        done.await(8, TimeUnit.SECONDS);

        // Verify framework messages were published for step start and completion.
        verify(langChainService, atLeast(2)).addFrameworkContext(anyString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TaskSnapshot snapshotFor(Step step) {
        Plan plan = new Plan(
                UUID.randomUUID(),
                "Test plan",
                "testplayer",
                Instant.now(),
                List.of(step),
                new TerminationPolicy(Duration.ofHours(1), false));
        return new TaskSnapshot(
                plan.id(), plan, TaskState.RUNNING, 0, "starting",
                Instant.now(), Optional.empty());
    }
}
