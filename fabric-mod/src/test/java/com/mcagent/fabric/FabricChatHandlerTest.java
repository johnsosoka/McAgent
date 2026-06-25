package com.mcagent.fabric;

import com.mcagent.core.service.BotOperations;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.fabric.observer.AutonomousObserver;
import com.mcagent.fabric.queue.BotEventQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FabricChatHandler} bypass command handling.
 *
 * <p>Tests call {@link FabricChatHandler#handleToggleCommand} directly with raw command
 * strings rather than going through {@link FabricChatHandler#onChatMessage}. This avoids
 * loading {@code Minecraft.getInstance()} which is unavailable in a headless test JVM.
 * The decision logic is identical regardless of the entry point.</p>
 */
@ExtendWith(MockitoExtension.class)
class FabricChatHandlerTest {

    @Mock
    private BotEventQueue botEventQueue;

    @Mock
    private AutonomousObserver autonomousObserver;

    @Mock
    private BotOperations botOperations;

    @Mock
    private JobTracker jobTracker;

    private Counters counters;
    private FabricChatHandler handler;
    private List<String> sentMessages;

    @BeforeEach
    void setUp() {
        counters = new Counters();
        sentMessages = new ArrayList<>();
        // Use the package-private constructor to inject a recording sender instead of
        // FabricChatSender.send(), which would try to load net.minecraft.client.Minecraft.
        handler = new FabricChatHandler(
                botEventQueue, autonomousObserver, botOperations, jobTracker,
                counters, sentMessages::add);
    }

    // --- Hard-stop bypass ---

    @Test
    void stop_cancelsBotClearsQueueClearsJob_sendsAck_returnsTrue() {
        boolean consumed = handler.handleToggleCommand("stop");

        assertThat(consumed).isTrue();
        verify(botOperations).cancel();
        verify(botEventQueue).clearInbound();
        verify(jobTracker).clearJob();
        assertThat(sentMessages).containsExactly("Stopped.");
        // No LLM enqueue
        verify(botEventQueue, never()).enqueueCommand(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancel_bypassesLlm_returnsTrue() {
        boolean consumed = handler.handleToggleCommand("cancel");

        assertThat(consumed).isTrue();
        verify(botOperations).cancel();
        verify(botEventQueue).clearInbound();
    }

    @Test
    void halt_bypassesLlm_returnsTrue() {
        boolean consumed = handler.handleToggleCommand("halt");

        assertThat(consumed).isTrue();
        verify(botOperations).cancel();
        verify(botEventQueue).clearInbound();
    }

    @Test
    void abort_bypassesLlm_returnsTrue() {
        boolean consumed = handler.handleToggleCommand("abort");

        assertThat(consumed).isTrue();
        verify(botOperations).cancel();
        verify(botEventQueue).clearInbound();
    }

    // --- Status bypass ---

    @Test
    void status_doesNotCancelBot_sendsStatusString_returnsTrue() {
        when(autonomousObserver.getMode()).thenReturn("passive");
        when(autonomousObserver.isEnabled()).thenReturn(true);
        when(jobTracker.getCurrentJob()).thenReturn(java.util.Optional.empty());
        when(jobTracker.getLastProgress()).thenReturn("");

        boolean consumed = handler.handleToggleCommand("status");

        assertThat(consumed).isTrue();
        verify(botOperations, never()).cancel();
        verify(botEventQueue, never()).clearInbound();
        verify(botEventQueue, never()).enqueueCommand(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        // Verify a status string was actually sent
        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0)).contains("Mode:").contains("Observer:").contains("Job:");
    }

    @Test
    void status_withActiveJob_includesJobDescriptionAndElapsed() {
        when(autonomousObserver.getMode()).thenReturn("active");
        when(autonomousObserver.isEnabled()).thenReturn(true);
        JobTracker.Job job = new JobTracker.Job("Mining IRON_ORE", java.time.Instant.now(), "Alice");
        when(jobTracker.getCurrentJob()).thenReturn(java.util.Optional.of(job));
        when(jobTracker.elapsedFormatted()).thenReturn("5s");
        when(jobTracker.getLastProgress()).thenReturn("42/500");

        handler.handleToggleCommand("status");

        assertThat(sentMessages).hasSize(1);
        String status = sentMessages.get(0);
        assertThat(status).contains("Mining IRON_ORE");
        assertThat(status).contains("5s");
        assertThat(status).contains("42/500");
    }

    @Test
    void status_noJob_reportsIdleWithoutThrowing() {
        // Exercises the TOCTOU-safe path: getCurrentJob() returns empty,
        // so the map shortcircuits and "idle" is used without a second call.
        when(autonomousObserver.getMode()).thenReturn("passive");
        when(autonomousObserver.isEnabled()).thenReturn(false);
        when(jobTracker.getCurrentJob()).thenReturn(java.util.Optional.empty());
        when(jobTracker.getLastProgress()).thenReturn("");

        handler.handleToggleCommand("status");

        assertThat(sentMessages).hasSize(1);
        String status = sentMessages.get(0);
        assertThat(status).contains("Job: idle");
        assertThat(status).contains("Observer: off");
    }

    @Test
    void whatAreYouDoing_handledAsStatusBypass_returnsTrue() {
        when(autonomousObserver.getMode()).thenReturn("active");
        when(autonomousObserver.isEnabled()).thenReturn(true);
        when(jobTracker.getCurrentJob()).thenReturn(java.util.Optional.empty());
        when(jobTracker.getLastProgress()).thenReturn("");

        boolean consumed = handler.handleToggleCommand("what are you doing");

        assertThat(consumed).isTrue();
        verify(botOperations, never()).cancel();
        assertThat(sentMessages).hasSize(1);
    }

    // --- Normal commands — NOT bypassed, return false ---

    @Test
    void comeHere_isNotBypassed_returnsFalse() {
        boolean consumed = handler.handleToggleCommand("come here");

        assertThat(consumed).isFalse();
        verify(botOperations, never()).cancel();
        verify(botEventQueue, never()).clearInbound();
    }

    @Test
    void mineIron_isNotBypassed_returnsFalse() {
        boolean consumed = handler.handleToggleCommand("mine iron");

        assertThat(consumed).isFalse();
        verify(botOperations, never()).cancel();
    }

    @Test
    void buildHouse_isNotBypassed_returnsFalse() {
        boolean consumed = handler.handleToggleCommand("build a house");

        assertThat(consumed).isFalse();
        verify(botOperations, never()).cancel();
    }

    // --- Observer toggles remain bypass ---

    @Test
    void watch_enablesObservation_returnsTrue() {
        boolean consumed = handler.handleToggleCommand("watch");

        assertThat(consumed).isTrue();
        verify(autonomousObserver).setEnabled(true);
        verify(botEventQueue, never()).enqueueCommand(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeMode_setsObserverMode_returnsTrue() {
        boolean consumed = handler.handleToggleCommand("active mode");

        assertThat(consumed).isTrue();
        verify(autonomousObserver).setMode("active");
    }

    @Test
    void passiveMode_setsObserverMode_returnsTrue() {
        boolean consumed = handler.handleToggleCommand("passive mode");

        assertThat(consumed).isTrue();
        verify(autonomousObserver).setMode("passive");
    }

    // --- Stats bypass ---

    @Test
    void stats_bypassesLlm_returnsTrue_andSendsCounterSnapshot() {
        // Pre-populate a few counters so the snapshot is non-empty.
        counters.inc("inbound.enqueued", 10L);
        counters.inc("outbound.sent", 8L);
        counters.inc("llm.calls", 5L);

        boolean consumed = handler.handleToggleCommand("stats");

        assertThat(consumed).isTrue();
        verify(botEventQueue, never()).enqueueCommand(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        assertThat(sentMessages).hasSize(1);
        String statsMsg = sentMessages.get(0);
        // The output must include the hand-picked key names from buildStatsString.
        assertThat(statsMsg).contains("inbound.enqueued=10");
        assertThat(statsMsg).contains("outbound.sent=8");
        assertThat(statsMsg).contains("llm.calls=5");
    }

    @Test
    void stats_emptyCounters_returnsZeroValues() {
        boolean consumed = handler.handleToggleCommand("stats");

        assertThat(consumed).isTrue();
        assertThat(sentMessages).hasSize(1);
        // All counters are zero — should still include all key names.
        String statsMsg = sentMessages.get(0);
        assertThat(statsMsg).contains("inbound.enqueued=0");
        assertThat(statsMsg).contains("llm.calls=0");
    }

    @Test
    void stats_resultFitsWithinChatLimit() {
        boolean consumed = handler.handleToggleCommand("stats");

        assertThat(consumed).isTrue();
        assertThat(sentMessages.get(0).length()).isLessThanOrEqualTo(250);
    }
}
