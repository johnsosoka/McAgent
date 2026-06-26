package com.mcagent.core.task;

import com.mcagent.core.service.BotOperations;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.RunLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of {@link TaskExecutor}.
 *
 * <p>Enforces one-plan-at-a-time semantics: submitting a second plan while one is
 * {@link TaskState#RUNNING} throws {@link IllegalStateException}. This is an explicit
 * architectural decision (SYNTHESIS §6) — the bot has a single goal at any moment.</p>
 *
 * <p>On each state transition, the {@link SessionState} is persisted so the plan
 * survives a disconnect. On reconnect, {@link McAgentFabricMod} (or a
 * {@code SessionResumer} bean) queries the repository and re-submits.</p>
 *
 * <h3>Thread safety</h3>
 * <p>{@link #current} is an {@link AtomicReference} so readers on any thread always
 * see a consistent snapshot. The {@code submit} method uses {@code compareAndSet} to
 * guard against a race where two callers simultaneously see {@code null} and both try
 * to submit.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTaskExecutor implements TaskExecutor {

    private final JobSupervisor jobSupervisor;
    private final SessionStateRepository sessionStateRepository;
    private final BotOperations bot;
    private final Counters counters;
    private final RunLog runLog;

    /** The single active task. Null means the bot is idle. */
    private final AtomicReference<TaskSnapshot> current = new AtomicReference<>(null);

    /** Address of the server the bot is connected to. Must be set via {@link #setServerAddress} before {@link #submit}. */
    private volatile String serverAddress = null;

    @Override
    public TaskSnapshot submit(Plan plan) {
        if (serverAddress == null) {
            throw new IllegalStateException(
                    "Cannot submit a plan before setServerAddress() is called. " +
                    "The fabric mod must set the server address after Spring context startup.");
        }

        TaskSnapshot initial = new TaskSnapshot(
                plan.id(),
                plan,
                TaskState.RUNNING,
                0,
                "starting",
                Instant.now(),
                Optional.empty());

        // CAS guard — reject if already running.
        if (!current.compareAndSet(null, initial)) {
            TaskSnapshot existing = current.get();
            throw new IllegalStateException(
                    "A plan is already running (id=" +
                    (existing != null ? existing.id() : "unknown") + "). Cancel it first.");
        }

        // Persist initial state before handing to the supervisor.
        persistSnapshot(initial);

        counters.inc("plan.submitted");
        runLog.log("plan_submitted", Map.of(
                "planId", plan.id().toString(),
                "summary", plan.summary(),
                "steps", plan.steps().size(),
                "requestedBy", plan.requestedBy()));

        log.info("DefaultTaskExecutor: submitted plan '{}' ({} steps)", plan.summary(), plan.steps().size());

        // Hand to JobSupervisor. State updates are fed back through the onUpdate consumer.
        jobSupervisor.submit(initial, bot, this::onTaskUpdate);

        return initial;
    }

    @Override
    public Optional<TaskSnapshot> currentTask() {
        return Optional.ofNullable(current.get())
                .filter(s -> s.state() == TaskState.RUNNING || s.state() == TaskState.PAUSED);
    }

    @Override
    public Optional<TaskSnapshot> snapshot(UUID taskId) {
        TaskSnapshot snap = current.get();
        if (snap != null && snap.id().equals(taskId)) {
            return Optional.of(snap);
        }
        return Optional.empty();
    }

    @Override
    public boolean cancel(UUID taskId) {
        TaskSnapshot snap = current.get();
        if (snap == null || !snap.id().equals(taskId)) {
            return false;
        }

        log.info("DefaultTaskExecutor: cancelling plan '{}'", snap.plan().summary());
        jobSupervisor.requestCancel();

        // The supervisor will call onTaskUpdate with CANCELLED state — we just signal.
        counters.inc("plan.cancelled");
        runLog.log("plan_cancelled", Map.of("planId", taskId.toString()));
        return true;
    }

    /**
     * Set the current server address used to scope SessionState persistence.
     * Called by the fabric layer ({@code McAgentFabricMod} or a {@code SessionResumer} bean)
     * after the Spring context starts and the server address is known.
     */
    public void setServerAddress(String address) {
        this.serverAddress = address != null ? address : "unknown";
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Called by {@link JobSupervisor} on every state transition. Updates the in-memory
     * snapshot and persists to the database.
     */
    private void onTaskUpdate(TaskSnapshot updated) {
        current.set(updated);
        persistSnapshot(updated);

        // If terminal, null out the current reference so the next plan can be submitted.
        TaskState state = updated.state();
        if (state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED) {
            current.compareAndSet(updated, null);

            if (state == TaskState.COMPLETED) {
                counters.inc("plan.completed");
            } else if (state == TaskState.FAILED) {
                counters.inc("plan.failed");
            }
        }
    }

    private void persistSnapshot(TaskSnapshot snap) {
        try {
            SessionState entity = sessionStateRepository
                    .findById(snap.id())
                    .orElseGet(SessionState::new);

            entity.setTaskId(snap.id());
            entity.setPlanJson(PlanSerializer.toJson(snap.plan()));
            entity.setState(snap.state());
            entity.setCurrentStepIndex(snap.currentStepIndex());
            entity.setStartedAt(snap.startedAt());
            entity.setLastFailureReason(snap.lastFailureReason().orElse(null));
            entity.setRequestedBy(snap.plan().requestedBy());
            entity.setServerAddress(serverAddress);

            sessionStateRepository.save(entity);
        } catch (Exception e) {
            // Persistence failure is non-fatal — the task continues in memory.
            log.error("DefaultTaskExecutor: failed to persist SessionState for task {}", snap.id(), e);
        }
    }
}
