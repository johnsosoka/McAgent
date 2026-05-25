package com.mcagent.core.service;

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

@ExtendWith(MockitoExtension.class)
class LangChain4jServiceTest {

    @Mock
    private Assistant assistant;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private BotOperations bot;

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
}
