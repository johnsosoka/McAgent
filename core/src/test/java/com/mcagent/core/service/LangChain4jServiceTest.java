package com.mcagent.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LangChain4jServiceTest {

    @Mock
    private Assistant assistant;

    @InjectMocks
    private LangChain4jService service;

    @Test
    void shouldReturnResponseOnSuccess() {
        when(assistant.chat("hi")).thenReturn("Hello!");

        String result = service.processInput("hi", "player-1");

        assertThat(result).isEqualTo("Hello!");
        verify(assistant).chat("hi");
    }

    @Test
    void shouldReturnNullOnException() {
        RuntimeException failure = new RuntimeException("LLM timeout");
        when(assistant.chat("complex query")).thenThrow(failure);

        String result = service.processInput("complex query", "player-2");

        assertThat(result).isNull();
    }
}
