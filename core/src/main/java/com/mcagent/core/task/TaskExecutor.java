package com.mcagent.core.task;

import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of {@link Plan} execution.
 *
 * <p>One Plan may run at a time (per the architectural decision in SYNTHESIS §6).
 * Submitting a second Plan while one is {@link TaskState#RUNNING} is rejected.</p>
 *
 * <p>All methods are safe to call from any thread.</p>
 */
public interface TaskExecutor {

    /**
     * Validate, persist, and begin executing the given Plan.
     *
     * @param plan the plan to run
     * @return a snapshot representing the initial running state
     * @throws IllegalStateException if a plan is already running
     */
    TaskSnapshot submit(Plan plan);

    /**
     * Return the snapshot of the currently running task, or empty if the bot is idle.
     */
    Optional<TaskSnapshot> currentTask();

    /**
     * Return the snapshot for a specific task ID, or empty if not found / not current.
     */
    Optional<TaskSnapshot> snapshot(UUID taskId);

    /**
     * Request cancellation of the task with the given ID.
     *
     * @return true if the task was found and cancellation was signalled;
     *         false if no matching task is current.
     */
    boolean cancel(UUID taskId);
}
