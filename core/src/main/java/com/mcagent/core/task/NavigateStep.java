package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * Instructs the bot to path to a specific (x, y, z) coordinate.
 *
 * <p>Success is defined as the bot's current position being within the
 * completion tolerance of the target (default: 2 blocks, handled in
 * {@link JobSupervisor}).</p>
 *
 * @param x       Target block X.
 * @param y       Target block Y.
 * @param z       Target block Z.
 * @param timeout Hard time limit for reaching the destination.
 * @param retry   Retry policy if pathing fails or times out.
 */
public record NavigateStep(
        @JsonProperty("x") int x,
        @JsonProperty("y") int y,
        @JsonProperty("z") int z,
        @JsonProperty("timeout") Duration timeout,
        @JsonProperty("retry") RetryPolicy retry
) implements Step {

    @JsonCreator
    public NavigateStep {
        // compact canonical constructor — annotations drive Jackson deserialization
    }

    @Override
    public String description() {
        return "Navigate to (" + x + ", " + y + ", " + z + ")";
    }
}
