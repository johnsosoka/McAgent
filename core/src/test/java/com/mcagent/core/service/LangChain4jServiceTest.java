package com.mcagent.core.service;

import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.RunLog;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private ChatMemory chatMemory;

    @Mock
    private BotOperations bot;

    @Mock
    private Counters counters;

    @Mock
    private RunLog runLog;

    @InjectMocks
    private LangChain4jService service;

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
}
