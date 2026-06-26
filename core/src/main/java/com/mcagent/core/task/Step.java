package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Duration;

/**
 * A single unit of work inside a {@link Plan}.
 *
 * <p>Steps are typed, declarative descriptions of what the bot should do — not
 * direct tool calls. The {@link JobSupervisor} dispatches each step to the
 * appropriate execution handler.</p>
 *
 * <p>Sprint 13 ships three concrete step types:
 * {@link NavigateStep}, {@link MineStep}, {@link WaitForStep}.
 * Sprint 14 will add Build, Craft, Smelt, Deposit, etc.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NavigateStep.class, name = "Navigate"),
        @JsonSubTypes.Type(value = MineStep.class,    name = "Mine"),
        @JsonSubTypes.Type(value = WaitForStep.class,  name = "WaitFor")
})
public sealed interface Step permits NavigateStep, MineStep, WaitForStep {

    /** Short human-readable summary used in framework messages and logging. */
    String description();

    /** Hard ceiling on how long this step may run. Null means inherit from the Plan. */
    Duration timeout();

    /** Retry configuration applied on step failure. */
    RetryPolicy retry();
}
