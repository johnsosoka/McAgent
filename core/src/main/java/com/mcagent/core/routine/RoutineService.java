package com.mcagent.core.routine;

import com.mcagent.core.task.Plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service for managing named, parameterized Routine templates.
 *
 * <p>A Routine is a {@link com.mcagent.core.task.Plan} template whose step arguments
 * may contain {@code ${param}} placeholders. At invocation time ({@link #invoke}),
 * the placeholders are substituted with caller-supplied values and the template is
 * deserialized into a runnable {@link Plan}.</p>
 */
public interface RoutineService {

    /**
     * Persist a new routine definition.
     *
     * @param name         Unique camelCase name (e.g. "returnHome").
     * @param description  One-line description for human display.
     * @param planTemplate JSON array of steps with optional {@code ${param}} placeholders.
     * @param parameters   Ordered list of parameter names the template expects.
     * @return The UUID of the newly created {@link RoutineDefinition}.
     * @throws IllegalArgumentException if a routine with {@code name} already exists.
     */
    UUID define(String name, String description, String planTemplate, List<String> parameters);

    /**
     * Resolve and return a runnable {@link Plan} for the named routine.
     *
     * <p>Each {@code ${param}} placeholder in the plan template is replaced with the
     * corresponding value from {@code args} before deserialization. Numeric values are
     * substituted as their JSON representations; strings are quoted.</p>
     *
     * @param name Routine name to look up.
     * @param args Map of parameter name → value to substitute.
     * @return A fully-constructed {@link Plan} ready to submit to {@link com.mcagent.core.task.TaskExecutor}.
     * @throws RoutineNotFoundException    if no routine with {@code name} exists.
     * @throws RoutineInvocationException  if substitution or deserialization fails.
     */
    Plan invoke(String name, Map<String, Object> args);

    /**
     * Find a routine by its unique name.
     *
     * @param name Routine name to look up.
     * @return {@link Optional} containing the definition, or empty if not found.
     */
    Optional<RoutineDefinition> findByName(String name);

    /**
     * Return all known routines, ordered by name.
     */
    List<RoutineDefinition> listAll();

    /**
     * Delete a routine by name.
     *
     * @param name Routine name to remove.
     * @return {@code true} if the routine existed and was deleted; {@code false} if not found.
     */
    boolean delete(String name);
}
