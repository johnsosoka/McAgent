package com.mcagent.core.telemetry;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Lightweight in-process counter registry.
 *
 * <p>All counter operations are lock-free via {@link AtomicLong}. The counter
 * map itself uses {@link ConcurrentHashMap} so readers and writers never block
 * each other across threads.</p>
 *
 * <p>Counters are created on first use — there is no need to register them
 * ahead of time. The zero-value is returned for any name that has never been
 * incremented.</p>
 */
@Component
public final class Counters {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * Increment {@code name} by 1 and return the new value.
     */
    public long inc(String name) {
        return inc(name, 1L);
    }

    /**
     * Increment {@code name} by {@code delta} and return the new value.
     */
    public long inc(String name, long delta) {
        return counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    /**
     * Return the current value of {@code name}, or 0 if it has never been incremented.
     */
    public long get(String name) {
        AtomicLong c = counters.get(name);
        return c == null ? 0L : c.get();
    }

    /**
     * Return an immutable snapshot of all counters and their current values.
     * The snapshot is consistent per-counter (each value is atomically read)
     * but not consistent across the whole map — new counters may appear or
     * existing ones may change between the start and end of this call.
     */
    public Map<String, Long> snapshot() {
        return counters.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().get()));
    }

    /**
     * Remove all counters. Primarily useful in tests.
     */
    public void reset() {
        counters.clear();
    }
}
