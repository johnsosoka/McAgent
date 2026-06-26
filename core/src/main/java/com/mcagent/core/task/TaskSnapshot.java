package com.mcagent.core.task;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable point-in-time view of a task's execution state.
 *
 * <p>Read by the {@code agent status} bypass command, the heartbeat (A3), and
 * the task-aware observer (A6) without acquiring any locks — all fields are
 * captured at the moment the snapshot is created.</p>
 *
 * @param id               Task identifier (same as {@link Plan#id()}).
 * @param plan             The plan being executed.
 * @param state            Current lifecycle state.
 * @param currentStepIndex Zero-based index of the step currently executing (or last attempted).
 * @param stepProgress     Free-form progress string, e.g. "23/64 iron mined" or "1m18s elapsed".
 * @param startedAt        Wall-clock time the task transitioned to {@link TaskState#RUNNING}.
 * @param lastFailureReason Human-readable failure description if the last step failed.
 */
public record TaskSnapshot(
        UUID id,
        Plan plan,
        TaskState state,
        int currentStepIndex,
        String stepProgress,
        Instant startedAt,
        Optional<String> lastFailureReason
) {}
