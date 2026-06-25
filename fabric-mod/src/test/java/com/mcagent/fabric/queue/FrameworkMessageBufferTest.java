package com.mcagent.fabric.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FrameworkMessageBuffer}.
 */
class FrameworkMessageBufferTest {

    private static final int CAPACITY = 5;
    private static final long THROTTLE_MS = 60_000L; // large enough that delivery never fires during tests

    private FrameworkMessageBuffer buffer;

    @AfterEach
    void tearDown() {
        if (buffer != null) {
            buffer.shutdown();
        }
    }

    @Test
    void seenInWindow_bounded_doesNotExceedCapacity() {
        List<String> delivered = new ArrayList<>();
        buffer = new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, delivered::add);

        // Publish capacity + 5 unique messages without triggering delivery (throttle is huge)
        int totalPublished = CAPACITY + 5;
        for (int i = 0; i < totalPublished; i++) {
            buffer.publish("message-" + i);
        }

        assertThat(buffer.seenInWindowSize())
                .as("seenInWindow must not exceed capacity")
                .isEqualTo(CAPACITY);
        assertThat(delivered).isEmpty();
    }

    @Test
    void publish_duplicateMessage_isDeduplicatedWithinWindow() {
        List<String> delivered = new ArrayList<>();
        buffer = new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, delivered::add);

        buffer.publish("status: ok");
        buffer.publish("status: ok");
        buffer.publish("status: ok");

        // Only one copy should be in the buffer (the others are deduplicated)
        assertThat(buffer.seenInWindowSize()).isEqualTo(1);
        assertThat(delivered).isEmpty();
    }

    @Test
    void publish_nullAndBlankMessages_areIgnored() {
        List<String> delivered = new ArrayList<>();
        buffer = new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, delivered::add);

        buffer.publish(null);
        buffer.publish("   ");
        buffer.publish("");

        assertThat(buffer.seenInWindowSize()).isEqualTo(0);
    }

    @Test
    void flush_deliversAllBufferedMessages() {
        List<String> delivered = new ArrayList<>();
        buffer = new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, delivered::add);

        buffer.publish("msg-a");
        buffer.publish("msg-b");
        buffer.flush();

        assertThat(delivered).containsExactly("msg-a", "msg-b");
    }

    @Test
    void evictedMessageIsRedeliverable() {
        // Capacity = 3, throttle huge so delivery never fires during the test.
        List<String> delivered = new ArrayList<>();
        buffer = new FrameworkMessageBuffer(3, THROTTLE_MS, delivered::add);

        // Fill buffer: m1, m2, m3 — all in seenInWindow.
        buffer.publish("m1");
        buffer.publish("m2");
        buffer.publish("m3");

        // Publishing m4 evicts m1 from the ring buffer.
        // The bug: without the fix, m1 stays in seenInWindow and the next publish("m1") is deduped.
        buffer.publish("m4");

        assertThat(buffer.bufferSize()).isEqualTo(3);
        // m1 must no longer be tracked in the dedup window so it can be re-accepted.
        assertThat(buffer.isSeenInWindow("m1"))
                .as("evicted m1 should have been removed from seenInWindow")
                .isFalse();

        // Re-publishing m1 must be accepted (not silently deduplicated).
        buffer.publish("m1");

        assertThat(buffer.isSeenInWindow("m1"))
                .as("re-published m1 should now be in seenInWindow")
                .isTrue();
        // Buffer should still be at capacity (m4 evicted by m1 re-entry).
        assertThat(buffer.bufferSize()).isEqualTo(3);
        assertThat(delivered).isEmpty();
    }
}
