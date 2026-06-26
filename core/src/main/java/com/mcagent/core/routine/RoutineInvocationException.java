package com.mcagent.core.routine;

/**
 * Thrown when a routine's plan template cannot be resolved into a valid
 * {@link com.mcagent.core.task.Plan} — e.g. missing parameters, malformed JSON,
 * or a deserialization error after substitution.
 */
public class RoutineInvocationException extends RuntimeException {

    public RoutineInvocationException(String message) {
        super(message);
    }

    public RoutineInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
