package com.mcagent.fabric.queue;

/**
 * Immutable record representing a player command enqueued for LLM processing.
 *
 * <p>{@code cmdId} is a short base-36 correlation ID generated at enqueue time
 * and propagated via SLF4J MDC through the dispatcher, LLM call, and tool
 * invocations so every log line for a given command can be correlated.</p>
 */
public record InboundEvent(
        String playerName,
        String command,
        EventPriority priority,
        long timestamp,
        String cmdId
) {
}
