package com.mcagent.core.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link Planner} AI-Service interface.
 *
 * <p>Tests verify that LangChain4j correctly deserializes the LLM's JSON output
 * into a {@link PlanDraft}, without making any real network calls. A fake
 * {@link ChatModel} returns a hand-crafted JSON response.</p>
 */
class PlannerTest {

    /**
     * Build a fake {@link ChatModel} that always returns the given text as the
     * assistant's response.
     */
    private static ChatModel fakeModelReturning(String responseText) {
        ChatModel model = mock(ChatModel.class);
        dev.langchain4j.data.message.AiMessage aiMessage =
                dev.langchain4j.data.message.AiMessage.from(responseText);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(response);
        return model;
    }

    private static Planner plannerWith(ChatModel model) {
        return AiServices.builder(Planner.class)
                .chatModel(model)
                .build();
    }

    @Test
    void propose_decodesSingleNavigateStep() {
        String json = """
                {
                  "summary": "Come to base",
                  "steps": [
                    {"type":"Navigate","x":100,"y":64,"z":200,"timeoutSeconds":120}
                  ]
                }
                """;

        Planner planner = plannerWith(fakeModelReturning(json));
        PlanDraft draft = planner.propose("come to base");

        assertThat(draft.summary()).isEqualTo("Come to base");
        assertThat(draft.steps()).hasSize(1);
        assertThat(draft.steps().get(0))
                .containsEntry("type", "Navigate")
                .containsEntry("x", 100)
                .containsEntry("y", 64)
                .containsEntry("z", 200);
    }

    @Test
    void propose_decodesMultipleSteps() {
        String json = """
                {
                  "summary": "Mine 64 iron ore",
                  "steps": [
                    {"type":"Mine","blockType":"minecraft:iron_ore","quantity":64,"searchRadius":32,"timeoutSeconds":1800},
                    {"type":"WaitFor","condition":{"type":"InventoryHas","itemId":"minecraft:iron_ore","count":64},"timeoutSeconds":600}
                  ]
                }
                """;

        Planner planner = plannerWith(fakeModelReturning(json));
        PlanDraft draft = planner.propose("mine 64 iron ore");

        assertThat(draft.summary()).isEqualTo("Mine 64 iron ore");
        assertThat(draft.steps()).hasSize(2);

        var mineStep = draft.steps().get(0);
        assertThat(mineStep).containsEntry("type", "Mine");
        assertThat(mineStep).containsEntry("blockType", "minecraft:iron_ore");
        assertThat(mineStep).containsEntry("quantity", 64);

        var waitStep = draft.steps().get(1);
        assertThat(waitStep).containsEntry("type", "WaitFor");
    }

    @Test
    void propose_summaryIsPresent() {
        String json = """
                {
                  "summary": "Explore the area",
                  "steps": [
                    {"type":"Navigate","x":0,"y":64,"z":0,"timeoutSeconds":60}
                  ]
                }
                """;

        Planner planner = plannerWith(fakeModelReturning(json));
        PlanDraft draft = planner.propose("explore area around origin");

        assertThat(draft.summary()).isNotBlank();
    }
}
