package com.mcagent.core.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The decision returned by {@link PlanFailureExplainer} when a plan step fails.
 *
 * <p>The explainer produces one of three actions:</p>
 * <ul>
 *   <li>{@code "RETRY"} — the failure was transient; retry the same step.</li>
 *   <li>{@code "REVISE"} — the plan needs structural change; a new plan JSON
 *       is provided in {@link #revisedPlan()}.</li>
 *   <li>{@code "ABANDON"} — the goal is infeasible from current state; send
 *       the {@link #explanation()} to the player and mark the task FAILED.</li>
 * </ul>
 *
 * @param explanation  1–2 sentence natural-language reason why the step failed.
 * @param action       One of {@code "RETRY"}, {@code "REVISE"}, or {@code "ABANDON"}.
 * @param revisedPlan  Full plan JSON map when {@code action == "REVISE"}; null otherwise.
 */
public record FailureDecision(
        @JsonProperty("explanation") String explanation,
        @JsonProperty("action") String action,
        @JsonProperty("revisedPlan") Map<String, Object> revisedPlan
) {
    public static final String RETRY   = "RETRY";
    public static final String REVISE  = "REVISE";
    public static final String ABANDON = "ABANDON";

    @JsonCreator
    public FailureDecision {
        // Allow null revisedPlan — it is expected for RETRY and ABANDON.
    }

    /** Returns true when this decision requires the explainer to provide a replacement plan. */
    public boolean isRevise() {
        return REVISE.equals(action);
    }

    /** Returns true when execution should stop and the player should be informed. */
    public boolean isAbandon() {
        return ABANDON.equals(action);
    }

    /** Returns true when the same step should be attempted again. */
    public boolean isRetry() {
        return RETRY.equals(action);
    }
}
