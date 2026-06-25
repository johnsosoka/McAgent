package com.mcagent.core.telemetry;

import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Utilities for short correlation IDs and SLF4J MDC scoping.
 *
 * <p>A correlation ID is a 6-character base-36 string that uniquely identifies
 * a single inbound command as it flows through the queue → LLM → tool-call →
 * outbound pipeline.  Six base-36 characters give 36^6 ≈ 2.2 billion distinct
 * values — short enough to grep comfortably, long enough that collisions within
 * a session are negligible.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * try (var scope = CorrelationId.scope(event.cmdId())) {
 *     // All log lines inside here carry cmd_id=<id> in the MDC.
 *     processInbound(event);
 * }
 * }</pre>
 */
public final class CorrelationId {

    /** SLF4J MDC key under which the correlation ID is stored. */
    public static final String MDC_KEY = "cmd_id";

    private CorrelationId() {
        // static utility class
    }

    /**
     * Generate a fresh 6-character base-36 ID.
     *
     * <p>Uses {@link ThreadLocalRandom} — fast and sufficient for correlation
     * purposes (not a security primitive).</p>
     */
    public static String generate() {
        long value = ThreadLocalRandom.current().nextLong(
                36L * 36 * 36 * 36 * 36 * 36  // 36^6
        );
        return Long.toString(value, 36);
    }

    /**
     * Open a try-with-resources MDC scope that stores {@code id} under
     * {@link #MDC_KEY} on entry and restores the previous value (or removes
     * the key) on close.
     *
     * @param id the correlation ID to bind; may be null (treated as empty string)
     */
    public static MdcScope scope(String id) {
        return new MdcScope(MDC_KEY, id != null ? id : "");
    }

    /**
     * Auto-closeable scope that sets an MDC key on construction and restores
     * the previous value on {@link #close()}.
     */
    public static final class MdcScope implements AutoCloseable {

        private final String key;
        private final String previous;

        MdcScope(String key, String value) {
            this.key = key;
            this.previous = MDC.get(key);
            MDC.put(key, value);
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        }
    }
}
