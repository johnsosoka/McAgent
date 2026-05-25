package com.mcagent.core.model;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * Result of a pathfinding or movement operation.
 */
@Data
@Builder
public class PathResult {
    private final boolean success;
    private final String message;
    private final int blocksTraveled;
    private final Duration duration;
    private final PathResultType type;

    public enum PathResultType {
        SUCCESS,
        BLOCKED,
        TIMEOUT,
        CANCELLED,
        ERROR
    }
}
