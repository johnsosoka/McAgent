package com.mcagent.core.routine;

/**
 * Thrown when a routine name does not exist in the {@link RoutineRepository}.
 */
public class RoutineNotFoundException extends RuntimeException {

    public RoutineNotFoundException(String name) {
        super("Routine not found: " + name);
    }
}
