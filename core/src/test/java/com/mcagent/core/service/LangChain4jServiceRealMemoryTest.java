package com.mcagent.core.service;

import com.mcagent.core.memory.chat.LayeredChatMemory;
import com.mcagent.core.memory.chat.RollingSummaryService;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.RunLog;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-memory integration tests for {@link LangChain4jService}.
 *
 * <p>These tests use an actual {@link LayeredChatMemory} (not a mock) so they can
 * verify the atomicity and single-injection guarantees of
 * {@link LangChain4jService#processUrgentObservation(String)}. They live in a
 * separate class so Mockito's strict-stubbing enforcement (from
 * {@code @ExtendWith(MockitoExtension.class)}) doesn't flag the inline
 * {@code mock(BotOperations.class)} calls as "unnecessary".</p>
 */
class LangChain4jServiceRealMemoryTest {

    // -------------------------------------------------------------------------
    // Single-thread tests
    // -------------------------------------------------------------------------

    /**
     * {@code processUrgentObservation} must add exactly one {@code <framework>} message
     * to memory, and call {@code assistant.chat} with the fixed reaction prompt rather
     * than restating the observation text.
     */
    @Test
    void urgentObservation_addsExactlyOneFrameworkMessage_andUsesReactionPrompt() {
        LayeredChatMemory realMemory = buildRealMemory();
        List<String> chatPrompts = Collections.synchronizedList(new ArrayList<>());
        Assistant recordingAssistant = msg -> {
            chatPrompts.add(msg);
            return "ok";
        };

        BotOperations fakeBotOps = mock(BotOperations.class);
        // processUrgentObservation does not call bot; no stubbing required
        LangChain4jService svc = new LangChain4jService(recordingAssistant, mock(PlanFailureExplainer.class), realMemory, fakeBotOps, noOpCounters(), noOpRunLog());

        svc.processUrgentObservation("Creeper detected 8 blocks away");

        // Exactly one <framework> message in memory
        long frameworkCount = realMemory.messages().stream()
                .filter(m -> m.type() == ChatMessageType.USER)
                .filter(m -> ((UserMessage) m).singleText().startsWith("<framework>"))
                .count();
        assertThat(frameworkCount).isEqualTo(1);

        // The assistant was called once, with the reaction prompt — not the observation text
        assertThat(chatPrompts).hasSize(1);
        assertThat(chatPrompts.get(0)).isEqualTo("React to the latest framework observation.");
        assertThat(chatPrompts.get(0)).doesNotContain("Creeper detected");
    }

    /**
     * {@code addFrameworkContext} must wrap the message in {@code <framework>} tags.
     */
    @Test
    void addFrameworkContext_wrapsInFrameworkTags() {
        LayeredChatMemory realMemory = buildRealMemory();
        BotOperations fakeBotOps = mock(BotOperations.class);
        LangChain4jService svc = new LangChain4jService(msg -> "ok", mock(PlanFailureExplainer.class), realMemory, fakeBotOps, noOpCounters(), noOpRunLog());

        svc.addFrameworkContext("Arrived at destination.");

        assertThat(realMemory.messages()).hasSize(1);
        UserMessage msg = (UserMessage) realMemory.messages().get(0);
        assertThat(msg.singleText()).isEqualTo("<framework>Arrived at destination.</framework>");
    }

    // -------------------------------------------------------------------------
    // Concurrent stress test
    // -------------------------------------------------------------------------

    /**
     * Concurrent stress test: 50 {@code processInput} and 50 {@code processUrgentObservation}
     * calls on parallel threads. Verifies:
     * <ul>
     *   <li>Every {@code <framework>} tag in every memory snapshot is properly closed —
     *       no partial writes from interleaved threads.</li>
     *   <li>No raw observation text appears in memory outside of {@code <framework>}
     *       wrapping (eliminates the old double-injection pattern where the observation
     *       was re-stated as the {@code chat()} argument text).</li>
     *   <li>All 100 thread tasks completed without deadlock.</li>
     * </ul>
     */
    @Test
    void concurrent_processInput_and_urgentObservation_noDuplicateInjection() throws Exception {
        int threadCount = 50;
        List<List<ChatMessage>> snapshots = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger callCount = new AtomicInteger(0);

        LayeredChatMemory realMemory = new LayeredChatMemory(
                40,   // large window — no eviction during this test
                100,  // high trigger — summary never fires
                buildNoOpSummaryService()
        );

        // Captures a memory snapshot on every assistant.chat() invocation
        Assistant recordingAssistant = msg -> {
            callCount.incrementAndGet();
            snapshots.add(List.copyOf(realMemory.messages()));
            return "ack";
        };

        BotOperations fakeBotOps = mock(BotOperations.class);
        when(fakeBotOps.getPlayerPosition(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        LangChain4jService svc = new LangChain4jService(recordingAssistant, mock(PlanFailureExplainer.class), realMemory, fakeBotOps, noOpCounters(), noOpRunLog());

        ExecutorService pool = Executors.newFixedThreadPool(threadCount * 2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                awaitQuietly(startGate);
                svc.processInput("player command " + idx, "player-" + idx);
            }));
            futures.add(pool.submit(() -> {
                awaitQuietly(startGate);
                svc.processUrgentObservation("observation-" + idx);
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(); // re-throws any exception from a worker thread
        }
        pool.shutdown();

        // Validate every snapshot taken at LLM call time
        for (List<ChatMessage> snapshot : snapshots) {
            // Every <framework> message must be well-formed
            snapshot.stream()
                    .filter(m -> m.type() == ChatMessageType.USER)
                    .map(m -> ((UserMessage) m).singleText())
                    .filter(t -> t.startsWith("<framework>"))
                    .forEach(text -> assertThat(text)
                            .as("Malformed <framework> tag in concurrent snapshot")
                            .endsWith("</framework>"));

            // The old double-injection emitted "Autonomous observation: ..." directly as a
            // UserMessage in memory. That pattern must be gone.
            List<String> illegalLeaks = snapshot.stream()
                    .filter(m -> m.type() == ChatMessageType.USER)
                    .map(m -> ((UserMessage) m).singleText())
                    .filter(t -> t.startsWith("Autonomous observation:"))
                    .toList();
            assertThat(illegalLeaks)
                    .as("Double-injection detected: observation text re-stated outside <framework>")
                    .isEmpty();
        }

        // Every submitted task ran — confirms no deadlock
        assertThat(callCount.get()).isEqualTo(threadCount * 2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LayeredChatMemory buildRealMemory() {
        return new LayeredChatMemory(20, 100, buildNoOpSummaryService());
    }

    private static Counters noOpCounters() {
        return new Counters();
    }

    private static RunLog noOpRunLog() {
        // Return a RunLog that is not started — log() calls are silently dropped
        // because running=false prevents enqueuing.
        return new RunLog(noOpCounters());
    }

    private RollingSummaryService buildNoOpSummaryService() {
        ChatModel noOpModel = new ChatModel() {
            @Override
            public String chat(String userMessage) {
                return "no-op summary";
            }
        };
        return new RollingSummaryService(noOpModel, 800);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
