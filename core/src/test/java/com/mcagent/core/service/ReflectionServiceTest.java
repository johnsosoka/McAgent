package com.mcagent.core.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReflectionService}.
 *
 * <p>Verifies that the AI-Service proxy correctly forwards a task context string to the
 * underlying {@link ChatModel} and returns its plain-text response unchanged.
 * No live LLM calls are made — a fake {@link ChatModel} returns a fixed {@link ChatResponse}.</p>
 */
class ReflectionServiceTest {

    private static final String FIXED_REFLECTION =
            "The navigation to coordinates completed without incident. " +
            "Mining the required iron ore took longer than expected due to depth constraints. " +
            "Next time, scan at Y=-16 from the start to reach ore-dense layers faster.";

    /**
     * Build a fake ChatModel that always returns the given plain text as the
     * assistant's response, wrapped in a proper ChatResponse.
     */
    private static ChatModel fakeModelReturning(String text) {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .finishReason(FinishReason.STOP)
                .build();
        when(model.chat(any(ChatRequest.class))).thenReturn(response);
        return model;
    }

    /**
     * Given a fake ChatModel that returns a fixed string, the ReflectionService proxy
     * must return that string verbatim.
     */
    @Test
    void reflect_returnsModelOutputVerbatim() {
        ReflectionService service = AiServices.builder(ReflectionService.class)
                .chatModel(fakeModelReturning(FIXED_REFLECTION))
                .build();

        String context = """
                Task: mine 8 iron ore
                Outcome: SUCCESS
                Steps completed: 2/2
                Wall time: 3 minutes (180s)
                """;

        String result = service.reflect(context);

        assertThat(result).isEqualTo(FIXED_REFLECTION);
    }

    /**
     * When the chat model returns a short response, it is still passed through unchanged.
     * The ReflectionService is a transparent proxy — it does not validate or modify content.
     */
    @Test
    void reflect_passesShortResponseThrough() {
        ReflectionService service = AiServices.builder(ReflectionService.class)
                .chatModel(fakeModelReturning("Mining worked. Nothing failed."))
                .build();

        String result = service.reflect("Task: dig hole\nOutcome: SUCCESS\n");

        assertThat(result).isEqualTo("Mining worked. Nothing failed.");
    }
}
