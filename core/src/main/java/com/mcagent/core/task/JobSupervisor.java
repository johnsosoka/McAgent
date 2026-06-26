package com.mcagent.core.task;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.FailureDecision;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.service.PlanDraft;
import com.mcagent.core.service.PlanFromDraftConverter;
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
import java.util.ArrayList;
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
    private final BotProperties botProperties;

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
        // Use a mutable list so REVISE can replace remaining steps mid-run.
        Plan plan = initial.plan();
        List<Step> steps = new ArrayList<>(plan.steps());
        Instant planStart = initial.startedAt();

        // Mutable state tracked inside the supervisor thread only.
        AtomicReference<TaskSnapshot> current = new AtomicReference<>(initial);

        // Number of LLM escalation calls made during this plan run.
        // Capped at bot.plan.max-escalations-per-plan to prevent thrash.
        int escalationCount = 0;
        int maxEscalations = botProperties.getPlan().getMaxEscalationsPerPlan();

        log.info("JobSupervisor: starting plan '{}' ({} steps, cap={})",
                plan.summary(), steps.size(), maxEscalations);

        try {
            for (int i = initial.currentStepIndex(); i < steps.size(); i++) {

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
                int stepCount = steps.size();
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
                    publishFramework("[task:" + plan.summary() + "][step " + (i + 1) + "/" + steps.size() + "] FAILED: " + reason);

                    // --- DEPS explain-then-replan (Q1) ---
                    EscalationOutcome outcome = handleStepFailure(
                            plan, steps, i, step, reason,
                            escalationCount, maxEscalations,
                            bot, current, onUpdate);
                    escalationCount = outcome.newEscalationCount();

                    if (outcome.shouldAbort()) {
                        return; // terminal state already set by handleStepFailure
                    }
                    if (outcome.shouldRetry()) {
                        i--;   // rewind loop counter; loop increment will bring it back to i
                        continue;
                    }
                    if (outcome.revisedStepList() != null) {
                        // REVISE: replace steps from current index onward with the new list.
                        List<Step> revised = outcome.revisedStepList();
                        steps.subList(i, steps.size()).clear();
                        steps.addAll(revised);
                        i--;   // step i will be re-attempted with the revised first step
                        continue;
                    }
                    // Defensive: if we somehow fall through without a clear directive, abort.
                    return;
                }

                runLog.log("plan_step_completed", Map.of(
                        "planId", plan.id().toString(),
                        "step", i,
                        "description", step.description()));
                publishFramework("[task:" + plan.summary() + "][step " + (i + 1) + "/" + steps.size() + "] done");
            }

            // All steps completed.
            log.info("JobSupervisor: plan '{}' completed", plan.summary());
            TaskSnapshot completed = withState(current.get(), TaskState.COMPLETED, steps.size() - 1, "done");
            onUpdate.accept(completed);
            counters.inc("plan.completed");
            runLog.log("plan_completed", Map.of("planId", plan.id().toString()));
            publishFramework("[task:" + plan.summary() + "] completed (" + steps.size() + " steps)");
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
     * Apply the DEPS explain-then-replan escalation for a failed step.
     *
     * <p>If the escalation cap has been reached, forces ABANDON without an LLM call.
     * Otherwise calls {@link LangChain4jService#processFailureEscalation} and interprets
     * the {@link FailureDecision}:</p>
     * <ul>
     *   <li>RETRY — caller should decrement step index and retry.</li>
     *   <li>REVISE — caller should replace remaining steps from the revised plan.</li>
     *   <li>ABANDON — task is marked FAILED; caller should return.</li>
     * </ul>
     */
    private EscalationOutcome handleStepFailure(
            Plan plan, List<Step> steps,
            int stepIndex, Step step, String reason,
            int escalationCount, int maxEscalations,
            BotOperations bot,
            AtomicReference<TaskSnapshot> current,
            Consumer<TaskSnapshot> onUpdate) {

        // Build world-state snapshot with whatever the bot can tell us cheaply.
        Map<String, Object> worldState = Map.of(
                "position", bot.getCurrentPosition().toString(),
                "stepIndex", stepIndex,
                "totalSteps", steps.size());

        int retriesUsed = step.retry() != null ? step.retry().maxRetries() : 0;

        TaskFailure failure = new TaskFailure(
                plan.id(),
                plan.summary(),
                stepIndex,
                steps.size(),
                step.description(),
                reason,
                retriesUsed,
                worldState);

        FailureDecision decision;

        if (escalationCount >= maxEscalations) {
            // Hard cap reached — abandon without another LLM call.
            log.warn("JobSupervisor: escalation cap ({}) reached for plan '{}'. Forcing ABANDON.",
                    maxEscalations, plan.summary());
            counters.inc("plan.escalation.cap_hit");
            decision = new FailureDecision(
                    "Max escalations reached (" + maxEscalations + "). Abandoning plan.",
                    FailureDecision.ABANDON, null);
        } else {
            decision = langChainService.processFailureEscalation(failure);
        }

        int newEscalationCount = escalationCount + 1;

        log.info("JobSupervisor: escalation #{} → action={} explanation='{}'",
                newEscalationCount, decision.action(), decision.explanation());
        runLog.log("plan_escalation", Map.of(
                "planId", plan.id().toString(),
                "step", stepIndex,
                "action", decision.action(),
                "escalationCount", newEscalationCount));

        return switch (decision.action()) {
            case FailureDecision.RETRY -> {
                publishFramework("[task:" + plan.summary() + "] escalation: retrying step " +
                        (stepIndex + 1) + " — " + decision.explanation());
                yield EscalationOutcome.retry(newEscalationCount);
            }

            case FailureDecision.REVISE -> {
                List<Step> revisedSteps = convertRevisedPlan(decision, plan, stepIndex, current, onUpdate);
                if (revisedSteps == null) {
                    // Conversion failed — escalate to ABANDON.
                    String abandonReason = "REVISE conversion failed; abandoning. " + reason;
                    markFailed(plan, stepIndex, abandonReason, current, onUpdate);
                    notifyPlayerAndFramework(plan, decision.explanation());
                    yield EscalationOutcome.abort(newEscalationCount);
                }
                publishFramework("[task:" + plan.summary() + "] plan revised — " + decision.explanation());
                yield EscalationOutcome.revise(revisedSteps, newEscalationCount);
            }

            default -> { // ABANDON (and any unexpected value)
                String abandonReason = decision.explanation() + " [original: " + reason + "]";
                markFailed(plan, stepIndex, abandonReason, current, onUpdate);
                notifyPlayerAndFramework(plan, decision.explanation());
                yield EscalationOutcome.abort(newEscalationCount);
            }
        };
    }

    /**
     * Convert the {@code revisedPlan} map from a {@link FailureDecision} into a typed
     * list of {@link Step}s using {@link PlanFromDraftConverter}.
     *
     * @return the revised steps, or null if conversion fails (caller should ABANDON)
     */
    @SuppressWarnings("unchecked")
    private List<Step> convertRevisedPlan(
            FailureDecision decision, Plan plan, int stepIndex,
            AtomicReference<TaskSnapshot> current, Consumer<TaskSnapshot> onUpdate) {

        if (decision.revisedPlan() == null) {
            log.error("JobSupervisor: REVISE decision has null revisedPlan for plan '{}'", plan.summary());
            return null;
        }

        try {
            Object rawSteps = decision.revisedPlan().get("steps");
            if (!(rawSteps instanceof List<?> stepList)) {
                log.error("JobSupervisor: revisedPlan.steps is not a List for plan '{}'", plan.summary());
                return null;
            }
            Object rawSummary = decision.revisedPlan().getOrDefault("summary", plan.summary());
            PlanDraft draft = new PlanDraft(
                    rawSummary.toString(),
                    (List<Map<String, Object>>) stepList);
            Plan revised = PlanFromDraftConverter.convert(draft, plan.requestedBy());
            return new ArrayList<>(revised.steps());
        } catch (Exception e) {
            log.error("JobSupervisor: failed to convert revised plan for '{}'", plan.summary(), e);
            counters.inc("plan.escalation.revise_conversion_error");
            return null;
        }
    }

    /** Mark the task as FAILED and fire the update callback. */
    private void markFailed(Plan plan, int stepIndex, String reason,
                             AtomicReference<TaskSnapshot> current,
                             Consumer<TaskSnapshot> onUpdate) {
        TaskSnapshot failed = withFailure(current.get(), stepIndex, reason);
        current.set(failed);
        onUpdate.accept(failed);
        counters.inc("plan.failed");
        runLog.log("plan_failed", Map.of(
                "planId", plan.id().toString(),
                "step", stepIndex,
                "reason", reason));
    }

    /** Send the explanation to the player via framework context and outbound chat. */
    private void notifyPlayerAndFramework(Plan plan, String explanation) {
        publishFramework("[task:" + plan.summary() + "] ABANDONED — " + explanation);
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
    // Inner value types
    // -------------------------------------------------------------------------

    private record StepResult(boolean succeeded, String failureReason) {
        static StepResult ok() { return new StepResult(true, null); }
        static StepResult fail(String reason) { return new StepResult(false, reason); }
    }

    /**
     * Result of a single escalation call.
     *
     * <p>Exactly one of the three action flags is true; the others are false.
     * {@code revisedStepList} is non-null only when {@code shouldRevise} is true.</p>
     */
    private record EscalationOutcome(
            boolean shouldAbort,
            boolean shouldRetry,
            List<Step> revisedStepList,
            int newEscalationCount
    ) {
        static EscalationOutcome abort(int count) {
            return new EscalationOutcome(true, false, null, count);
        }
        static EscalationOutcome retry(int count) {
            return new EscalationOutcome(false, true, null, count);
        }
        static EscalationOutcome revise(List<Step> steps, int count) {
            return new EscalationOutcome(false, false, steps, count);
        }
    }
}
