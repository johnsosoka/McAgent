package com.mcagent.core.telemetry;

import com.mcagent.core.service.ChatService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dispatcher stall detector.
 *
 * <p>Every 10 seconds the watchdog checks whether the inbound dispatcher has made
 * progress. "Progress" is defined as: the {@code inbound.dispatched} counter
 * increased since the last check. If the queue is non-empty (pending events > 0)
 * and no dispatch has occurred in {@value #STALL_THRESHOLD_MS} ms, the bot is
 * likely hung — usually on an LLM round-trip that timed out or a blocking tool call.</p>
 *
 * <p>On detecting a stall the watchdog:</p>
 * <ul>
 *   <li>Logs a {@code WARN}-level message.</li>
 *   <li>Writes a {@code watchdog_stall} event to {@link RunLog}.</li>
 *   <li>Sends a single alert to the player via {@link ChatService}.</li>
 * </ul>
 *
 * <p>Alerts are suppressed for {@value #ALERT_COOLDOWN_MS} ms after each fire
 * so a persistent stall does not spam the player.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class Watchdog {

    static final long STALL_THRESHOLD_MS = 30_000L;
    static final long ALERT_COOLDOWN_MS = 60_000L;
    private static final long CHECK_INTERVAL_SECONDS = 10L;

    private final Counters counters;
    private final ChatService chatService;
    private final RunLog runLog;

    private ScheduledExecutorService scheduler;

    /** Snapshot of {@code inbound.dispatched} from the previous check. */
    private long lastDispatchedSeen = 0L;

    /**
     * Timestamp of the first tick where we noticed the dispatcher had not made
     * progress while the queue was non-empty. Reset when progress resumes.
     */
    private long stallSinceMs = 0L;

    /** Timestamp of the most recent alert. Prevents alert storms on a persistent stall. */
    private long lastAlertAtMs = 0L;

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mc-agent-watchdog");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
                this::check,
                CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        log.debug("Watchdog started (stall threshold={}ms, cooldown={}ms)",
                STALL_THRESHOLD_MS, ALERT_COOLDOWN_MS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // Package-private for testing without needing reflection.
    void check() {
        try {
            long enqueued = counters.get("inbound.enqueued");
            long dispatched = counters.get("inbound.dispatched");
            long pending = enqueued - dispatched;
            long now = System.currentTimeMillis();

            if (pending > 0 && dispatched == lastDispatchedSeen) {
                // Dispatcher has not made progress and there are commands waiting.
                if (stallSinceMs == 0L) {
                    stallSinceMs = now;
                }
                long stalledForMs = now - stallSinceMs;
                if (stalledForMs >= STALL_THRESHOLD_MS && (now - lastAlertAtMs) > ALERT_COOLDOWN_MS) {
                    String msg = "Watchdog: dispatcher stalled (pending=" + pending
                            + ", stalled=" + (stalledForMs / 1000) + "s). Bot may be hung on an LLM call.";
                    log.warn(msg);
                    runLog.log("watchdog_stall", Map.of("pending", pending, "stalled_ms", stalledForMs));
                    try {
                        chatService.send("[mc-agent] " + msg);
                    } catch (Exception e) {
                        log.warn("Watchdog: failed to send stall alert", e);
                    }
                    lastAlertAtMs = now;
                }
            } else {
                // Progress observed — reset stall tracking.
                lastDispatchedSeen = dispatched;
                stallSinceMs = 0L;
            }
        } catch (Throwable t) {
            log.error("Watchdog check failed", t);
        }
    }
}
