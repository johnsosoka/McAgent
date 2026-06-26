package com.mcagent.core.service;

/**
 * Thrown by {@link PlanFromDraftConverter} when a {@link PlanDraft} produced by the
 * {@link Planner} fails structural validation.
 *
 * <p>Common causes:
 * <ul>
 *   <li>An unknown step {@code "type"} value.</li>
 *   <li>A required field (e.g. {@code "x"}, {@code "blockType"}) is missing or the
 *       wrong Java type.</li>
 *   <li>The {@code summary} or {@code steps} list is null or empty.</li>
 * </ul>
 * </p>
 *
 * <p>The {@code decomposeAndExecute} tool catches this and returns a human-readable
 * error message to the player without escalating to the LLM's error path.</p>
 */
public class PlanValidationException extends RuntimeException {

    public PlanValidationException(String message) {
        super(message);
    }
}
