package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * Controls when a {@link Plan} should be automatically terminated regardless of step completion.
 *
 * @param maxDuration    Hard wall-clock cap on the entire Plan's execution.
 * @param stopOnLowHealth If true, the executor halts the Plan when bot health falls critically low.
 */
public record TerminationPolicy(
        @JsonProperty("maxDuration") Duration maxDuration,
        @JsonProperty("stopOnLowHealth") boolean stopOnLowHealth
) {
    @JsonCreator
    public TerminationPolicy {
        // compact canonical constructor
    }

    /**
     * Sensible default: 3-hour cap, stop when health is critical.
     */
    public static final TerminationPolicy DEFAULT = new TerminationPolicy(Duration.ofHours(3), true);
}
