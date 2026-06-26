package com.mcagent.core.task;

/**
 * Structured failure information for a task step.
 *
 * <p>The {@code code} is a machine-readable constant; {@code detail} carries
 * human-readable context for framework messages and LLM escalation.</p>
 */
public record FailureReason(String code, String detail) {

    public static final String NO_BLOCKS_FOUND  = "NO_BLOCKS_FOUND";
    public static final String BARITONE_FAILED   = "BARITONE_FAILED";
    public static final String TIMEOUT           = "TIMEOUT";
    public static final String INVENTORY_FULL    = "INVENTORY_FULL";
    public static final String CANCELLED         = "CANCELLED";
    public static final String HEALTH_CRITICAL   = "HEALTH_CRITICAL";

    @Override
    public String toString() {
        return code + ": " + detail;
    }
}
