package com.mcagent.core.task;

import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Single-thread supervisor that ticks the active {@link Plan} forward step by step.
 *
 * <p>The executor thread is named {@code mc-agent-job-supervisor} and is the only
 * thread that calls step-execution logic. The {@link DefaultTaskExecutor} hands work
 * to this class via {@link #submit(TaskSnapshot, Consumer)}.</p>
 *
 * <h3>Step-execution approach</h3>
 * <p>For Sprint 13, step execution uses tight polling loops (checked every
 * {@link #POLL_INTERVAL_MS} ms) rather than Baritone callback-driven completion.
 * This is a deliberate trade-off: callbacks would be more efficient but require
 * wiring through the fabric-mod layer, which would break the core/fabric separation.
 * The polling interval is short enough that latency is imperceptible to players.
 * A callback-based approach is recorded as a deferred decision for Sprint 14.</p>
 *
 * <h3>Thread safety</h3>
 * <p>{@link #cancelRequested} is {@code volatile} and checked between every step
 * and at every poll tick within a step. The supervisor thread is the only writer
 * of step-execution state; {@link DefaultTaskExecutor} is the only writer of the
 * cancel flag.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobSupervisor {

    /** How often the supervisor polls for step completion or cancellation (milliseconds). */
    static final long POLL_INTERVAL_MS = 500;

    /** Tolerance in blocks for "arrived at destination" check in NavigateStep. */
    private static final double NAVIGATE_TOLERANCE = 3.0;

    private final LangChain4jService langChainService;
    private final SessionStateRepository sessionStateRepository;
    private final JobTracker jobTracker;
    private final Counters counters;
    private final RunLog runLog;

    private ExecutorService executor;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile Future<?> currentFuture;

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mc-agent-job-supervisor");
            t.setDaemon(true);
            return t;
        });
        cancelRequested.set(false);
        log.info("JobSupervisor started.");
    }

    @PreDestroy
    void stop() {
        log.info("JobSupervisor shutting down.");
        if (cancelRequested != null) {
            cancelRequested.set(true);
        }
        if (currentFuture != null) {
            currentFuture.cancel(true);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("JobSupervisor stopped.");
    }

    /**
     * Schedule a plan snapshot for execution. The consumer is called on each state
     * transition so the caller ({@link DefaultTaskExecutor}) can keep its in-memory
     * snapshot current and persist changes.
     *
     * @param initial   initial snapshot in {@link TaskState#RUNNING} state
     * @param bot       the bot operations to delegate step execution to
     * @param onUpdate  called after every state update with the latest snapshot
     */
    public void submit(TaskSnapshot initial, BotOperations bot, Consumer<TaskSnapshot> onUpdate) {
        cancelRequested.set(false);
        currentFuture = executor.submit(() -> runPlan(initial, bot, onUpdate));
    }

    /**
     * Signal the supervisor to cancel the currently running plan.
     * The cancel flag is checked at every poll tick and between steps.
     */
    void requestCancel() {
        cancelRequested.set(true);
        Future<?> f = currentFuture;
        if (f != null) {
            f.cancel(true); // interrupt the poll sleep if blocked
        }
    }

    // -------------------------------------------------------------------------
    // Internal execution loop
    // -------------------------------------------------------------------------

    private void runPlan(TaskSnapshot initial, BotOperations bot, Consumer<TaskSnapshot> onUpdate) {
        Plan plan = initial.plan();
        List<Step> steps = plan.steps();
        int stepCount = steps.size();
        Instant planStart = initial.startedAt();

        // Mutable state tracked inside the supervisor thread only.
        AtomicReference<TaskSnapshot> current = new AtomicReference<>(initial);

        log.info("JobSupervisor: starting plan '{}' ({} steps)", plan.summary(), stepCount);

        try {
            for (int i = initial.currentStepIndex(); i < stepCount; i++) {

                // Check cancellation between steps.
                if (cancelRequested.get()) {
                    log.info("JobSupervisor: cancelled before step {}", i);
                    TaskSnapshot cancelled = withState(current.get(), TaskState.CANCELLED, i, "");
                    onUpdate.accept(cancelled);
                    runLog.log("plan_cancelled", Map.of("planId", plan.id().toString(), "atStep", i));
                    bot.cancel();
                    return;
                }

                // Check plan-level termination policy.
                if (plan.termination() != null && plan.termination().maxDuration() != null) {
                    Duration elapsed = Duration.between(planStart, Instant.now());
                    if (elapsed.compareTo(plan.termination().maxDuration()) >= 0) {
                        log.warn("JobSupervisor: plan '{}' exceeded maxDuration", plan.summary());
                        String reason = FailureReason.TIMEOUT + ": plan duration exceeded " + plan.termination().maxDuration();
                        TaskSnapshot failed = withFailure(current.get(), i, reason);
                        onUpdate.accept(failed);
                        counters.inc("plan.failed");
                        runLog.log("plan_failed", Map.of("planId", plan.id().toString(), "reason", reason));
                        publishFramework("[task:" + plan.summary() + "] plan timed out after " + plan.termination().maxDuration());
                        bot.cancel();
                        return;
                    }
                }

                Step step = steps.get(i);
                log.info("JobSupervisor: step {}/{} — {}", i + 1, stepCount, step.description());

                // Update snapshot to show which step is starting.
                TaskSnapshot stepping = withState(current.get(), TaskState.RUNNING, i, "starting…");
                current.set(stepping);
                onUpdate.accept(stepping);

                jobTracker.setCurrentJob(step.description(), plan.requestedBy());
                runLog.log("plan_step_started", Map.of(
                        "planId", plan.id().toString(),
                        "step", i,
                        "description", step.description()));
                publishFramework("[task:" + plan.summary() + "][step " + (i + 1) + "/" + stepCount + "] started: " + step.description());

                StepResult result = executeWithRetry(step, i, stepCount, plan, bot, current, onUpdate);

                if (cancelRequested.get()) {
                    log.info("JobSupervisor: cancelled during step {}", i);
                    TaskSnapshot cancelled = withState(current.get(), TaskState.CANCELLED, i, "");
                    onUpdate.accept(cancelled);
                    runLog.log("plan_cancelled", Map.of("planId", plan.id().toString(), "atStep", i));
                    bot.cancel();
                    return;
                }

                if (!result.succeeded()) {
                    String reason = result.failureReason();
                    log.warn("JobSupervisor: step {} failed — {}", i, reason);
                    TaskSnapshot failed = withFailure(current.get(), i, reason);
                    current.set(failed);
                    onUpdate.accept(failed);
                    counters.inc("plan.failed");
                    runLog.log("plan_failed", Map.of(
                            "planId", plan.id().toString(),
                            "step", i,
                            "reason", reason));
                    publishFramework("[task:" + plan.summary() + "][step " + (i + 1) + "/" + stepCount + "] FAILED: " + reason);
                    // Failure escalation (structured ping to LLM) — Sprint 13 v1: inject framework message.
                    // Full DEPS-style explain-then-replan is deferred to Sprint 14 Q1.
                    langChainService.addFrameworkContext(
                            "Plan step failed. Task: " + plan.summary() +
                            ". Step " + (i + 1) + "/" + stepCount + ": " + step.description() +
                            ". Reason: " + reason + ". Plan halted. Ask the player how to proceed.");
                    return;
                }

                runLog.log("plan_step_completed", Map.of(
                        "planId", plan.id().toString(),
                        "step", i,
                        "description", step.description()));
                publishFramework("[task:" + plan.summary() + "][step " + (i + 1) + "/" + stepCount + "] done");
            }

            // All steps completed.
            log.info("JobSupervisor: plan '{}' completed", plan.summary());
            TaskSnapshot completed = withState(current.get(), TaskState.COMPLETED, stepCount - 1, "done");
            onUpdate.accept(completed);
            counters.inc("plan.completed");
            runLog.log("plan_completed", Map.of("planId", plan.id().toString()));
            publishFramework("[task:" + plan.summary() + "] completed (" + stepCount + " steps)");
            jobTracker.clearJob();

        } catch (Throwable t) {
            log.error("JobSupervisor crashed unexpectedly", t);
            try {
                TaskSnapshot crashed = withFailure(current.get(), current.get().currentStepIndex(),
                        "Supervisor exception: " + t.getMessage());
                onUpdate.accept(crashed);
            } catch (Exception ignored) {
                // Best-effort — if the update callback itself throws, swallow it to avoid masking the original error.
            }
        }
    }

    /**
     * Execute a single step with retry support.
     * Returns the last step result — which preserves the original failure reason from the
     * step executor rather than wrapping it in a generic "N attempt(s) failed" message.
     */
    private StepResult executeWithRetry(Step step, int stepIndex, int stepCount, Plan plan,
                                         BotOperations bot,
                                         AtomicReference<TaskSnapshot> current,
                                         Consumer<TaskSnapshot> onUpdate) {
        RetryPolicy retry = step.retry() != null ? step.retry() : RetryPolicy.NONE;
        int attempts = 0;
        int maxAttempts = 1 + retry.maxRetries();
        StepResult lastResult = StepResult.fail("step did not execute");

        while (attempts < maxAttempts) {
            if (cancelRequested.get()) {
                return StepResult.fail(FailureReason.CANCELLED + ": cancelled by player");
            }

            if (attempts > 0) {
                log.info("JobSupervisor: retrying step {} (attempt {}/{})", stepIndex, attempts + 1, maxAttempts);
                if (!retry.backoff().isZero()) {
                    sleep(retry.backoff().toMillis());
                }
            }

            lastResult = executeStep(step, stepIndex, stepCount, plan, bot, current, onUpdate);
            if (lastResult.succeeded() || cancelRequested.get()) {
                return lastResult;
            }

            attempts++;
            if (attempts < maxAttempts) {
                log.warn("JobSupervisor: step {} failed (attempt {}/{}): {}", stepIndex, attempts, maxAttempts, lastResult.failureReason());
            }
        }

        return lastResult;
    }

    /**
     * Dispatch a step to the appropriate handler.
     */
    private StepResult executeStep(Step step, int stepIndex, int stepCount, Plan plan,
                                    BotOperations bot,
                                    AtomicReference<TaskSnapshot> current,
                                    Consumer<TaskSnapshot> onUpdate) {
        return switch (step) {
            case NavigateStep nav -> executeNavigate(nav, stepIndex, stepCount, plan, bot, current, onUpdate);
            case MineStep mine    -> executeMine(mine, stepIndex, stepCount, plan, bot, current, onUpdate);
            case WaitForStep wait -> executeWaitFor(wait, stepIndex, stepCount, plan, bot, current, onUpdate);
        };
    }

    /**
     * NavigateStep: issue bot.navigateTo then poll position until within tolerance or timeout.
     */
    private StepResult executeNavigate(NavigateStep nav, int stepIndex, int stepCount, Plan plan,
                                        BotOperations bot,
                                        AtomicReference<TaskSnapshot> current,
                                        Consumer<TaskSnapshot> onUpdate) {
        var result = bot.navigateTo(nav.x(), nav.y(), nav.z());
        if (!result.isSuccess()) {
            return StepResult.fail(FailureReason.BARITONE_FAILED + ": " + result.getMessage());
        }

        Duration timeout = nav.timeout() != null ? nav.timeout() : Duration.ofMinutes(5);
        Instant deadline = Instant.now().plus(timeout);
        BotOperations.Location target = new BotOperations.Location(nav.x(), nav.y(), nav.z());

        while (Instant.now().isBefore(deadline)) {
            if (cancelRequested.get()) {
                return StepResult.fail(FailureReason.CANCELLED);
            }

            BotOperations.Location pos = bot.getCurrentPosition();
            double dist = pos.distanceTo(target);
            String progress = String.format("%.0f blocks from target", dist);
            updateProgress(current, stepIndex, progress, onUpdate);
            jobTracker.updateProgress(progress);

            if (dist <= NAVIGATE_TOLERANCE) {
                return StepResult.ok();
            }

            sleep(POLL_INTERVAL_MS);
        }

        return StepResult.fail(FailureReason.TIMEOUT + ": did not arrive within " + timeout);
    }

    /**
     * MineStep: issue bot.mine then poll until mining stops or timeout.
     *
     * <p>The polling approach monitors mining state by checking the item count
     * in inventory. This is a pragmatic choice that keeps the executor in core/
     * without coupling to Baritone's internal process state. A callback-driven
     * approach is deferred (see class-level note on deferred decisions).</p>
     */
    private StepResult executeMine(MineStep mine, int stepIndex, int stepCount, Plan plan,
                                    BotOperations bot,
                                    AtomicReference<TaskSnapshot> current,
                                    Consumer<TaskSnapshot> onUpdate) {
        int startCount = bot.countItem(mine.blockType());
        var result = bot.mine(mine.blockType(), mine.quantity(), mine.searchRadius());
        if (!result.isSuccess()) {
            return StepResult.fail(FailureReason.BARITONE_FAILED + ": " + result.getMessage());
        }

        Duration timeout = mine.timeout() != null ? mine.timeout() : Duration.ofMinutes(10);
        Instant deadline = Instant.now().plus(timeout);

        // Poll until target quantity reached, Baritone finishes, or timeout.
        while (Instant.now().isBefore(deadline)) {
            if (cancelRequested.get()) {
                return StepResult.fail(FailureReason.CANCELLED);
            }

            int currentCount = bot.countItem(mine.blockType());
            int gained = currentCount - startCount;
            String progress = gained + "/" + mine.quantity() + " " + mine.blockType() + " mined";
            updateProgress(current, stepIndex, progress, onUpdate);
            jobTracker.updateProgress(progress);

            if (mine.quantity() > 0 && gained >= mine.quantity()) {
                return StepResult.ok();
            }

            // Check if bot's inventory is full (a common early-stop scenario).
            String invSummary = bot.getInventorySummary();
            if (invSummary != null && invSummary.toLowerCase().contains("full")) {
                return StepResult.fail(FailureReason.INVENTORY_FULL + ": inventory full after " + gained + " " + mine.blockType());
            }

            sleep(POLL_INTERVAL_MS);
        }

        // On timeout, treat as failure if quantity not reached; otherwise success.
        int gained = bot.countItem(mine.blockType()) - startCount;
        if (mine.quantity() > 0 && gained < mine.quantity()) {
            return StepResult.fail(FailureReason.TIMEOUT + ": mined " + gained + "/" + mine.quantity() + " before timeout");
        }
        return StepResult.ok();
    }

    /**
     * WaitForStep: poll the condition every {@link #POLL_INTERVAL_MS} ms until satisfied or timeout.
     */
    private StepResult executeWaitFor(WaitForStep wait, int stepIndex, int stepCount, Plan plan,
                                       BotOperations bot,
                                       AtomicReference<TaskSnapshot> current,
                                       Consumer<TaskSnapshot> onUpdate) {
        Duration timeout = wait.timeout() != null ? wait.timeout() : Duration.ofMinutes(5);
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            if (cancelRequested.get()) {
                return StepResult.fail(FailureReason.CANCELLED);
            }

            boolean satisfied = evaluateCondition(wait.condition(), bot);
            String progress = satisfied ? "condition met" : "waiting: " + wait.condition().describe();
            updateProgress(current, stepIndex, progress, onUpdate);

            if (satisfied) {
                return StepResult.ok();
            }

            sleep(POLL_INTERVAL_MS);
        }

        return StepResult.fail(FailureReason.TIMEOUT + ": condition not met within " + timeout + ": " + wait.condition().describe());
    }

    /**
     * Evaluate a WaitCondition against the bot's current state.
     */
    private boolean evaluateCondition(WaitCondition condition, BotOperations bot) {
        return switch (condition) {
            case InventoryHasCondition inv -> bot.hasItem(inv.itemId(), inv.count());
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void publishFramework(String message) {
        try {
            langChainService.addFrameworkContext(message);
        } catch (Exception e) {
            log.warn("JobSupervisor: failed to publish framework message: {}", e.getMessage());
        }
    }

    private void updateProgress(AtomicReference<TaskSnapshot> current, int stepIndex,
                                 String progress, Consumer<TaskSnapshot> onUpdate) {
        TaskSnapshot updated = new TaskSnapshot(
                current.get().id(),
                current.get().plan(),
                current.get().state(),
                stepIndex,
                progress,
                current.get().startedAt(),
                current.get().lastFailureReason());
        current.set(updated);
        onUpdate.accept(updated);
    }

    private TaskSnapshot withState(TaskSnapshot snap, TaskState state, int stepIndex, String progress) {
        return new TaskSnapshot(snap.id(), snap.plan(), state, stepIndex, progress,
                snap.startedAt(), snap.lastFailureReason());
    }

    private TaskSnapshot withFailure(TaskSnapshot snap, int stepIndex, String reason) {
        return new TaskSnapshot(snap.id(), snap.plan(), TaskState.FAILED, stepIndex, "",
                snap.startedAt(), Optional.of(reason));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Inner value type
    // -------------------------------------------------------------------------

    private record StepResult(boolean succeeded, String failureReason) {
        static StepResult ok() { return new StepResult(true, null); }
        static StepResult fail(String reason) { return new StepResult(false, reason); }
    }
}
