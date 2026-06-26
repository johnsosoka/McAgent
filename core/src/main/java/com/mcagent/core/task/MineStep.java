package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * Instructs the bot to mine a specific block type up to a given quantity.
 *
 * <p>Delegates to {@code BotOperations.mine(blockType, quantity, searchRadius)} and
 * polls Baritone progress until the target quantity is reached, the search radius
 * is exhausted, or the step times out.</p>
 *
 * @param blockType    Minecraft block ID, e.g. {@code "minecraft:iron_ore"}.
 * @param quantity     Number of blocks to mine. Use 0 for unbounded (governed by TerminationPolicy).
 * @param searchRadius Block-radius around the bot to search for the target block type.
 * @param timeout      Hard time limit for this step.
 * @param retry        Retry policy applied when Baritone exhausts its search.
 */
public record MineStep(
        @JsonProperty("blockType") String blockType,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("searchRadius") int searchRadius,
        @JsonProperty("timeout") Duration timeout,
        @JsonProperty("retry") RetryPolicy retry
) implements Step {

    @JsonCreator
    public MineStep {
        // compact canonical constructor — annotations drive Jackson deserialization
    }

    @Override
    public String description() {
        return "Mine " + quantity + " " + blockType + " (radius " + searchRadius + ")";
    }
}
