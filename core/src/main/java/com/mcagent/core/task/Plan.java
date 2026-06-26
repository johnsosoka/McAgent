package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A structured, typed, persistent description of multi-step work the bot should perform.
 *
 * <p>Plans are created by the LLM (via the {@code submitPlan} tool), persisted as
 * {@link SessionState}, and executed step-by-step by {@link JobSupervisor}.
 * Once submitted, the LLM is not re-invoked unless a step fails or the player sends
 * a new message.</p>
 *
 * <p>The step list is defensively copied on construction — callers cannot mutate a
 * Plan after it is built.</p>
 *
 * @param id          Unique identifier assigned at submission time.
 * @param summary     Short human-readable description.
 * @param requestedBy Name of the player who triggered this plan.
 * @param createdAt   Wall-clock timestamp of plan creation.
 * @param steps       Ordered list of steps to execute.
 * @param termination Controls when the plan should be auto-terminated.
 */
public record Plan(
        @JsonProperty("id") UUID id,
        @JsonProperty("summary") String summary,
        @JsonProperty("requestedBy") String requestedBy,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("steps") List<Step> steps,
        @JsonProperty("termination") TerminationPolicy termination
) {
    @JsonCreator
    public Plan {
        // Defensive copy prevents external mutation of the steps list.
        steps = List.copyOf(steps);
    }
}
