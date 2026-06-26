package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * Retry configuration applied to individual {@link Step} executions.
 *
 * @param maxRetries Number of additional attempts after the first failure. 0 means no retry.
 * @param backoff    Pause before each retry attempt.
 */
public record RetryPolicy(
        @JsonProperty("maxRetries") int maxRetries,
        @JsonProperty("backoff") Duration backoff
) {
    @JsonCreator
    public RetryPolicy {
        // compact canonical constructor
    }

    /** No retry — fail immediately on the first failure. */
    public static final RetryPolicy NONE = new RetryPolicy(0, Duration.ZERO);

    /** One retry after 2-second backoff — reasonable default for transient failures. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(1, Duration.ofSeconds(2));
}
