package com.mcagent.core.service;

import com.mcagent.core.model.BotResponse;
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
    void shouldReturnBotResponseOnSuccess() {
        BotResponse expected = BotResponse.builder()
                .message("Hello!")
                .confidence(BotResponse.Confidence.HIGH)
                .build();
        when(assistant.chat("hi")).thenReturn(expected);

        BotResponse result = service.processInput("hi", "player-1");

        assertThat(result.getMessage()).isEqualTo("Hello!");
        assertThat(result.getConfidence()).isEqualTo(BotResponse.Confidence.HIGH);
        verify(assistant).chat("hi");
    }

    @Test
    void shouldReturnLowConfidenceFallbackOnException() {
        RuntimeException failure = new RuntimeException("LLM timeout");
        when(assistant.chat("complex query")).thenThrow(failure);

        BotResponse result = service.processInput("complex query", "player-2");

        assertThat(result.getMessage()).contains("Sorry, I'm having trouble thinking right now.");
        assertThat(result.getMessage()).contains("LLM timeout");
        assertThat(result.getConfidence()).isEqualTo(BotResponse.Confidence.LOW);
        assertThat(result.isRequiresConfirmation()).isFalse();
    }
}
