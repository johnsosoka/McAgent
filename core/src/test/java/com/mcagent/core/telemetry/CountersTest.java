package com.mcagent.core.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Counters}.
 */
class CountersTest {

    private Counters counters;

    @BeforeEach
    void setUp() {
        counters = new Counters();
    }

    @Test
    void get_returnsZero_forNeverIncrementedCounter() {
        assertThat(counters.get("any.counter")).isEqualTo(0L);
    }

    @Test
    void inc_byOne_incrementsAndReturnsNewValue() {
        long first = counters.inc("foo");
        long second = counters.inc("foo");
        long third = counters.inc("foo");

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
        assertThat(third).isEqualTo(3L);
        assertThat(counters.get("foo")).isEqualTo(3L);
    }

    @Test
    void inc_byDelta_addsCorrectAmount() {
        counters.inc("latency", 120L);
        counters.inc("latency", 80L);

        assertThat(counters.get("latency")).isEqualTo(200L);
    }

    @Test
    void snapshot_containsAllCounters() {
        counters.inc("a");
        counters.inc("b", 5L);
        counters.inc("c", 10L);

        Map<String, Long> snap = counters.snapshot();

        assertThat(snap).containsEntry("a", 1L)
                .containsEntry("b", 5L)
                .containsEntry("c", 10L);
    }

    @Test
    void snapshot_isUnmodifiable() {
        counters.inc("x");
        Map<String, Long> snap = counters.snapshot();

        // Mutating the snapshot must not throw on every JVM, but it must be unmodifiable.
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> snap.put("x", 999L));
    }

    @Test
    void reset_clearsAllCounters() {
        counters.inc("alpha");
        counters.inc("beta", 42L);
        counters.reset();

        assertThat(counters.get("alpha")).isEqualTo(0L);
        assertThat(counters.get("beta")).isEqualTo(0L);
        assertThat(counters.snapshot()).isEmpty();
    }

    @Test
    void differentCounterNames_areIndependent() {
        counters.inc("queue.enqueued");
        counters.inc("queue.dropped");
        counters.inc("queue.dropped");

        assertThat(counters.get("queue.enqueued")).isEqualTo(1L);
        assertThat(counters.get("queue.dropped")).isEqualTo(2L);
    }

    /**
     * 100 threads each incrementing the same counter 100 times must produce
     * exactly 10 000 — verifying the lock-free {@link java.util.concurrent.atomic.AtomicLong}
     * implementation is correct under contention.
     */
    @Test
    void concurrentIncrements_producesExactTotal() throws InterruptedException {
        int threads = 100;
        int incsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < incsPerThread; i++) {
                    counters.inc("concurrent.counter");
                }
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(counters.get("concurrent.counter")).isEqualTo((long) threads * incsPerThread);
    }
}
