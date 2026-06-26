package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * Blocks plan progress until a {@link WaitCondition} is satisfied.
 *
 * <p>The {@link JobSupervisor} polls the condition every 500 ms until it returns
 * true or the step times out. Useful as a precondition gate before a step that
 * depends on inventory state.</p>
 *
 * @param condition The condition to poll.
 * @param timeout   Maximum time to wait before treating the step as failed.
 * @param retry     Retry policy applied on timeout.
 */
public record WaitForStep(
        @JsonProperty("condition") WaitCondition condition,
        @JsonProperty("timeout") Duration timeout,
        @JsonProperty("retry") RetryPolicy retry
) implements Step {

    @JsonCreator
    public WaitForStep {
        // compact canonical constructor — annotations drive Jackson deserialization
    }

    @Override
    public String description() {
        return "Wait for " + condition.describe();
    }
}
