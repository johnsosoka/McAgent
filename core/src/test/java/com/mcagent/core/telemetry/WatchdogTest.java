package com.mcagent.core.telemetry;

import com.mcagent.core.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link Watchdog}.
 *
 * <p>Tests call {@link Watchdog#check()} directly rather than relying on the
 * scheduler to fire, so we get deterministic, fast tests without sleeping.
 * The stall clock is advanced by manipulating the counter values rather than
 * by sleeping the test thread.</p>
 */
@ExtendWith(MockitoExtension.class)
class WatchdogTest {

    @Mock
    private ChatService chatService;

    @Mock
    private RunLog runLog;

    private Counters counters;
    private Watchdog watchdog;

    @BeforeEach
    void setUp() {
        counters = new Counters();
        watchdog = new Watchdog(counters, chatService, runLog);
        // Do NOT call start() — we drive check() directly.
    }

    @Test
    void noQueuePending_doesNotAlert() {
        // enqueued == dispatched == 0 → pending = 0
        watchdog.check();
        watchdog.check();
        watchdog.check();

        verify(chatService, never()).send(anyString());
    }

    @Test
    void pendingEvents_butDispatcherMakesProgress_doesNotAlert() {
        counters.inc("inbound.enqueued", 5L);
        counters.inc("inbound.dispatched", 3L);
        // First check: pending=2, dispatched=3 (different from lastDispatchedSeen=0)
        watchdog.check(); // progress seen → lastDispatchedSeen = 3, stallSinceMs = 0

        counters.inc("inbound.dispatched", 1L); // now 4
        watchdog.check(); // progress again → no stall

        verify(chatService, never()).send(anyString());
    }

    @Test
    void pendingEvents_dispatcherStalled_alertsAfterThreshold() {
        counters.inc("inbound.enqueued", 3L);
        // dispatched stays at 0 — stall starts immediately

        // Tick 1: stall starts (stallSinceMs set to now)
        watchdog.check();
        verify(chatService, never()).send(anyString());

        // Directly manipulate stallSinceMs to simulate time passing past the threshold.
        forceStalledSince(watchdog, System.currentTimeMillis() - Watchdog.STALL_THRESHOLD_MS - 1000);

        // Tick 2: stall exceeds threshold → alert fires
        watchdog.check();
        verify(chatService).send(anyString());
    }

    @Test
    void pendingEvents_stalled_alertsOnlyOncePerCooldown() {
        counters.inc("inbound.enqueued", 3L);
        watchdog.check(); // stallSinceMs set

        // Push stallSince and lastAlert well into the past
        forceStalledSince(watchdog, System.currentTimeMillis() - Watchdog.STALL_THRESHOLD_MS - 1000);

        watchdog.check(); // fires first alert
        verify(chatService).send(anyString());

        // Immediately check again — within cooldown, should NOT fire a second alert
        watchdog.check();
        // Still only one interaction with chatService
        verify(chatService).send(anyString()); // exactly once total
    }

    @Test
    void pendingEvents_stalled_alertsAgainAfterCooldownExpires() throws Exception {
        counters.inc("inbound.enqueued", 3L);
        watchdog.check();

        // Stall exceeds threshold
        forceStalledSince(watchdog, System.currentTimeMillis() - Watchdog.STALL_THRESHOLD_MS - 1000);
        watchdog.check(); // first alert

        // Push lastAlertAt into the past past the cooldown
        forceLastAlertAt(watchdog, System.currentTimeMillis() - Watchdog.ALERT_COOLDOWN_MS - 1000);

        // Check again — should fire a second alert
        watchdog.check();
        // Two total alerts
        org.mockito.Mockito.verify(chatService, org.mockito.Mockito.times(2)).send(anyString());
    }

    @Test
    void stalledThenProgress_resetsStallTracking() {
        counters.inc("inbound.enqueued", 3L);
        watchdog.check(); // stall starts

        // Simulate dispatcher making progress
        counters.inc("inbound.dispatched", 3L);
        watchdog.check(); // progress → resets stallSinceMs

        // Now force stall threshold (as if no progress since reset)
        forceStalledSince(watchdog, 0L); // stallSinceMs = 0 means not stalled
        watchdog.check();

        verify(chatService, never()).send(anyString());
    }

    @Test
    void sendThrowsRuntimeException_alertStillAttempted_andWatchdogSurvives() {
        // Arrange: chatService.send() throws on every call.
        doThrow(new RuntimeException("client disconnected")).when(chatService).send(anyString());

        counters.inc("inbound.enqueued", 3L);
        // Stall starts on first check.
        watchdog.check();

        // Advance stallSince past threshold.
        forceStalledSince(watchdog, System.currentTimeMillis() - Watchdog.STALL_THRESHOLD_MS - 1000);

        // Act: first alert — send() throws, but check() must not propagate the exception.
        watchdog.check();

        // Assert: alert was attempted despite the throw.
        verify(chatService).send(anyString());

        // Push lastAlertAt into the past so a second alert is eligible.
        forceLastAlertAt(watchdog, System.currentTimeMillis() - Watchdog.ALERT_COOLDOWN_MS - 1000);

        // Act: second check — watchdog must still be alive and attempt another alert.
        watchdog.check();

        verify(chatService, times(2)).send(anyString());
    }

    // -------------------------------------------------------------------------
    // Helpers that reach into Watchdog's private fields via reflection so tests
    // can control the clock without sleeping.
    // -------------------------------------------------------------------------

    private static void forceStalledSince(Watchdog watchdog, long millis) {
        setField(watchdog, "stallSinceMs", millis);
    }

    private static void forceLastAlertAt(Watchdog watchdog, long millis) {
        setField(watchdog, "lastAlertAtMs", millis);
    }

    private static void setField(Object target, String fieldName, long value) {
        try {
            java.lang.reflect.Field f = Watchdog.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.setLong(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Could not set field " + fieldName, e);
        }
    }
}
