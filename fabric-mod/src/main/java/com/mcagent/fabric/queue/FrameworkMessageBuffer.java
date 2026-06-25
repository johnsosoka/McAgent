package com.mcagent.fabric.queue;

import com.mcagent.core.telemetry.Counters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Ring buffer for Baritone and game framework status messages.
 *
 * <p>Features:</p>
 * <ul>
 *   <li><b>Capacity bound:</b> Oldest messages are dropped when the buffer is full.</li>
 *   <li><b>Deduplication:</b> Identical messages within the throttle window are collapsed.</li>
 *   <li><b>Throttling:</b> Messages are delivered to the consumer at most once per throttle interval.</li>
 *   <li><b>Batching:</b> If multiple distinct messages accumulate, they are delivered as a single digest.</li>
 * </ul>
 */
public class FrameworkMessageBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc_agent");

    private final Deque<String> buffer;
    private final Set<String> seenInWindow;
    private final int capacity;
    private final long throttleMs;
    private final Consumer<String> consumer;
    private final Counters counters;

    private final ScheduledExecutorService scheduler;
    private volatile long lastDeliveryTime;
    private volatile boolean shutdown;

    /**
     * @param capacity   max messages to retain in the ring buffer
     * @param throttleMs minimum millis between deliveries to the consumer
     * @param consumer   callback that receives batched / throttled messages
     */
    public FrameworkMessageBuffer(int capacity, long throttleMs, Consumer<String> consumer) {
        this(capacity, throttleMs, consumer, new Counters());
    }

    /**
     * @param capacity   max messages to retain in the ring buffer
     * @param throttleMs minimum millis between deliveries to the consumer
     * @param consumer   callback that receives batched / throttled messages
     * @param counters   counter registry for operational metrics
     */
    public FrameworkMessageBuffer(int capacity, long throttleMs, Consumer<String> consumer, Counters counters) {
        this.capacity = capacity;
        this.throttleMs = throttleMs;
        this.consumer = consumer;
        this.counters = counters;
        this.buffer = new ArrayDeque<>(capacity);
        this.seenInWindow = Collections.newSetFromMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        });
        this.lastDeliveryTime = 0;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mc-agent-framework-buffer");
            t.setDaemon(true);
            return t;
        });
        // Check for pending deliveries every throttle interval / 2
        this.scheduler.scheduleWithFixedDelay(
                this::tryDeliver, throttleMs / 2, throttleMs / 2, TimeUnit.MILLISECONDS
        );
    }

    /**
     * Publish a framework status message into the buffer.
     * Duplicate messages within the current throttle window are ignored.
     */
    public synchronized void publish(String message) {
        if (message == null || message.isBlank() || shutdown) {
            return;
        }
        String normalized = message.trim();
        if (seenInWindow.contains(normalized)) {
            LOGGER.debug("Deduplicated framework message: {}", normalized);
            counters.inc("framework.deduped");
            return;
        }
        if (buffer.size() >= capacity) {
            String dropped = buffer.pollFirst();
            seenInWindow.remove(dropped);
            LOGGER.debug("Dropped oldest framework message (capacity {}): {}", capacity, dropped);
            counters.inc("framework.dropped");
        }
        buffer.addLast(normalized);
        seenInWindow.add(normalized);
        counters.inc("framework.published");
    }

    /**
     * Attempt to deliver buffered messages if the throttle interval has elapsed.
     */
    private synchronized void tryDeliver() {
        if (buffer.isEmpty() || shutdown) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDeliveryTime < throttleMs) {
            return;
        }

        String payload;
        if (buffer.size() == 1) {
            payload = buffer.pollFirst();
            counters.inc("framework.delivered");
        } else {
            // Batch digest
            StringBuilder sb = new StringBuilder();
            sb.append("Status updates: ");
            int count = 0;
            while (!buffer.isEmpty() && count < capacity) {
                if (count > 0) sb.append(" | ");
                sb.append(buffer.pollFirst());
                count++;
            }
            payload = sb.toString();
            counters.inc("framework.delivered");
            counters.inc("framework.batched");
        }

        seenInWindow.clear();
        lastDeliveryTime = now;

        try {
            consumer.accept(payload);
        } catch (Exception e) {
            LOGGER.error("Framework message consumer failed", e);
        }
    }

    /**
     * Force immediate delivery of any buffered messages, bypassing throttle.
     * Used during graceful shutdown.
     */
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        lastDeliveryTime = System.currentTimeMillis();
        seenInWindow.clear();
        while (!buffer.isEmpty()) {
            String msg = buffer.pollFirst();
            try {
                consumer.accept(msg);
            } catch (Exception e) {
                LOGGER.error("Framework message consumer failed during flush", e);
            }
        }
    }

    /**
     * Shut down the background scheduler. Call {@link #flush()} first if you
     * want to ensure pending messages are delivered.
     */
    public void shutdown() {
        shutdown = true;
        scheduler.shutdownNow();
    }

    /**
     * Returns the number of messages currently tracked in the deduplication window.
     *
     * @VisibleForTesting
     */
    synchronized int seenInWindowSize() {
        return seenInWindow.size();
    }

    /**
     * Returns the number of messages currently held in the ring buffer.
     *
     * @VisibleForTesting
     */
    synchronized int bufferSize() {
        return buffer.size();
    }

    /**
     * Returns {@code true} if the given message is currently tracked in the dedup window.
     *
     * @VisibleForTesting
     */
    synchronized boolean isSeenInWindow(String message) {
        return seenInWindow.contains(message);
    }
}
