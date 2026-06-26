package com.mcagent.core.task;

/**
 * Lifecycle state of a task (Plan) being executed by the bot.
 *
 * <p>{@link #PENDING_APPROVAL} is a pre-execution hold state. Plans that exceed
 * the complexity thresholds configured in {@code BotProperties.PlanProperties}
 * are stored in {@link com.mcagent.core.task.PendingApprovalStore} and
 * presented to the player for review before execution begins. The player
 * confirms with {@code agent go} or {@code agent approved}.</p>
 */
public enum TaskState {
    QUEUED,
    PENDING_APPROVAL,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
