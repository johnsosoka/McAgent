package com.mcagent.fabric.queue;

/**
 * Immutable record representing a player command enqueued for LLM processing.
 */
public record InboundEvent(
        String playerName,
        String command,
        EventPriority priority,
        long timestamp
) {
}
