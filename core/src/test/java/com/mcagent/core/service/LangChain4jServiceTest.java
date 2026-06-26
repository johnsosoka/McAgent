package com.mcagent.core.service;

import com.mcagent.core.memory.episodic.EpisodicMemoryService;
import com.mcagent.core.memory.episodic.SessionContext;
import com.mcagent.core.task.TaskFailure;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.RunLog;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LangChain4jService} using Mockito.
 *
 * <p>These tests verify wiring and error-handling behaviour using mocked
 * collaborators. Real-memory and concurrency tests live in
 * {@link LangChain4jServiceRealMemoryTest} to avoid Mockito's strict-stub
 * enforcement interfering with inline {@code mock()} calls.</p>
 */
@ExtendWith(MockitoExtension.class)
class LangChain4jServiceTest {

    @Mock
    private Assistant assistant;

    @Mock
    private PlanFailureExplainer failureExplainer;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private BotOperations bot;

    @Mock
    private Counters counters;

    @Mock
    private RunLog runLog;

    @Mock
    private EpisodicMemoryService episodicMemoryService;

    @Mock
    private SessionContext sessionContext;

    @InjectMocks
    private LangChain4jService service;

    @BeforeEach
    void setUpEpisodicDefaults() {
        // Default: no stored memories so retrieval doesn't inject a <memories> block.
        // Tests that verify memory injection set their own stubs.
        lenient().when(episodicMemoryService.retrieve(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        lenient().when(sessionContext.getSessionId()).thenReturn("test-session");
    }

    @Test
    void shouldReturnResponseOnSuccess() {
        when(bot.getPlayerPosition("player-1")).thenReturn(java.util.Optional.empty());
        when(assistant.chat("hi")).thenReturn("Hello!");

        String result = service.processInput("hi", "player-1");

        assertThat(result).isEqualTo("Hello!");
        verify(chatMemory).add(any(dev.langchain4j.data.message.ChatMessage.class));
        verify(assistant).chat("hi");
    }

    @Test
    void shouldReturnNullOnException() {
        when(bot.getPlayerPosition("player-2")).thenReturn(java.util.Optional.empty());
        RuntimeException failure = new RuntimeException("LLM timeout");
        when(assistant.chat("complex query")).thenThrow(failure);

        String result = service.processInput("complex query", "player-2");

        assertThat(result).isNull();
    }

    @Test
    void processInput_incrementsLlmCallsCounter() {
        when(bot.getPlayerPosition("p")).thenReturn(java.util.Optional.empty());
        when(assistant.chat("hi")).thenReturn("ok");

        service.processInput("hi", "p");

        verify(counters).inc("llm.calls");
    }

    @Test
    void processInput_incrementsLlmErrorsCounterOnException() {
        when(bot.getPlayerPosition("p")).thenReturn(java.util.Optional.empty());
        when(assistant.chat("bad")).thenThrow(new RuntimeException("timeout"));

        service.processInput("bad", "p");

        verify(counters).inc("llm.errors");
    }

    @Test
    void processUrgentObservation_incrementsUrgentCallsCounter() {
        when(assistant.chat("React to the latest framework observation.")).thenReturn("ok");

        service.processUrgentObservation("creeper spotted");

        verify(counters).inc("llm.urgent.calls");
    }

    @Test
    void processUrgentObservation_incrementsUrgentErrorsCounterOnException() {
        when(assistant.chat("React to the latest framework observation."))
                .thenThrow(new RuntimeException("LLM error"));

        service.processUrgentObservation("creeper spotted");

        verify(counters).inc("llm.urgent.errors");
    }

    // -------------------------------------------------------------------------
    // processFailureEscalation tests
    // -------------------------------------------------------------------------

    @Test
    void processFailureEscalation_callsExplainerAndReturnsDecision() {
        FailureDecision expected = new FailureDecision("Chunk not loaded.", FailureDecision.RETRY, null);
        when(failureExplainer.explain(anyString())).thenReturn(expected);

        FailureDecision result = service.processFailureEscalation(sampleFailure());

        assertThat(result.action()).isEqualTo(FailureDecision.RETRY);
        assertThat(result.explanation()).isEqualTo("Chunk not loaded.");
    }

    @Test
    void processFailureEscalation_incrementsCallsCounter() {
        FailureDecision decision = new FailureDecision("ok", FailureDecision.ABANDON, null);
        when(failureExplainer.explain(anyString())).thenReturn(decision);

        service.processFailureEscalation(sampleFailure());

        verify(counters).inc("llm.failure_explain.calls");
    }

    @Test
    void processFailureEscalation_incrementsLatencyCounter_onSuccess() {
        FailureDecision decision = new FailureDecision("ok", FailureDecision.ABANDON, null);
        when(failureExplainer.explain(anyString())).thenReturn(decision);

        service.processFailureEscalation(sampleFailure());

        verify(counters).inc(org.mockito.ArgumentMatchers.eq("llm.failure_explain.latency.ms.sum"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void processFailureEscalation_returnsAbandon_whenExplainerThrows() {
        when(failureExplainer.explain(anyString())).thenThrow(new RuntimeException("network error"));

        FailureDecision result = service.processFailureEscalation(sampleFailure());

        assertThat(result.action()).isEqualTo(FailureDecision.ABANDON);
        assertThat(result.explanation()).contains("Unable to analyse failure");
    }

    @Test
    void processFailureEscalation_incrementsErrorsCounter_whenExplainerThrows() {
        when(failureExplainer.explain(anyString())).thenThrow(new RuntimeException("boom"));

        service.processFailureEscalation(sampleFailure());

        verify(counters).inc("llm.failure_explain.errors");
    }

    // -------------------------------------------------------------------------
    // Episodic memory integration tests
    // -------------------------------------------------------------------------

    @Test
    void processInput_injectsMemoriesFrameworkMessage_whenMemoriesPresent() {
        com.mcagent.core.memory.episodic.AgentEvent mem =
                new com.mcagent.core.memory.episodic.AgentEvent(
                        "plan_complete", "mine 64 iron completed in 720s", 7,
                        java.time.Instant.parse("2025-06-15T10:00:00Z"),
                        "test-session", "[]", null);
        when(episodicMemoryService.retrieve("mine iron", "test-session", 3))
                .thenReturn(List.of(mem));
        when(bot.getPlayerPosition("p")).thenReturn(java.util.Optional.empty());
        when(assistant.chat("mine iron")).thenReturn("ok");

        service.processInput("mine iron", "p");

        // A <memories>...</memories> framework message should be injected before player context.
        // We verify via chatMemory.add(ChatMessage) — the single-message overload.
        verify(chatMemory, org.mockito.Mockito.atLeast(1)).add(
                (dev.langchain4j.data.message.ChatMessage) org.mockito.ArgumentMatchers.argThat(msg -> {
                    if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                        return um.singleText().contains("<memories>")
                                && um.singleText().contains("plan_complete")
                                && um.singleText().contains("mine 64 iron completed");
                    }
                    return false;
                }));
    }

    @Test
    void processInput_doesNotInjectMemories_whenNoneRetrieved() {
        when(episodicMemoryService.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(bot.getPlayerPosition("p")).thenReturn(java.util.Optional.empty());
        when(assistant.chat("hi")).thenReturn("ok");

        service.processInput("hi", "p");

        // Only the player_context message should be injected — never a <memories> block.
        verify(chatMemory, org.mockito.Mockito.never()).add(
                (dev.langchain4j.data.message.ChatMessage) org.mockito.ArgumentMatchers.argThat(msg -> {
                    if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                        return um.singleText().contains("<memories>");
                    }
                    return false;
                }));
    }

    @Test
    void processInput_writesCommandEvent_afterSuccessfulLlmCall() {
        when(bot.getPlayerPosition("p")).thenReturn(java.util.Optional.empty());
        when(assistant.chat("mine iron")).thenReturn("ok");

        service.processInput("mine iron", "p");

        verify(episodicMemoryService).write(
                org.mockito.ArgumentMatchers.eq("command"),
                org.mockito.ArgumentMatchers.eq("mine iron"),
                org.mockito.ArgumentMatchers.eq("test-session"),
                any());
    }

    @Test
    void processInput_doesNotWriteCommandEvent_onLlmFailure() {
        when(bot.getPlayerPosition("p")).thenReturn(java.util.Optional.empty());
        when(assistant.chat("bad")).thenThrow(new RuntimeException("LLM failure"));

        service.processInput("bad", "p");

        verify(episodicMemoryService, org.mockito.Mockito.never()).write(anyString(), anyString(), anyString(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TaskFailure sampleFailure() {
        return new TaskFailure(
                UUID.randomUUID(),
                "Mine 8 iron ore",
                0, 1,
                "Mine minecraft:iron_ore x8",
                "TIMEOUT: did not complete within 30s",
                0,
                Map.of("position", "100,64,200"));
    }
}
