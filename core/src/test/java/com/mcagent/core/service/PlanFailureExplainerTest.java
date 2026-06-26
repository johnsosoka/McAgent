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
 * Unit tests for {@link PlanFailureExplainer}.
 *
 * <p>Tests verify that LangChain4j correctly deserialises the LLM's JSON output
 * into a {@link FailureDecision}, without making any real network calls. A fake
 * {@link ChatModel} returns hand-crafted JSON responses.</p>
 */
class PlanFailureExplainerTest {

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

    private static PlanFailureExplainer explainerWith(ChatModel model) {
        return AiServices.builder(PlanFailureExplainer.class)
                .chatModel(model)
                .build();
    }

    @Test
    void explain_deserialisesRetryDecision() {
        String json = """
                {
                  "explanation": "The chunk was not loaded yet — a transient timeout.",
                  "action": "RETRY",
                  "revisedPlan": null
                }
                """;

        PlanFailureExplainer explainer = explainerWith(fakeModelReturning(json));
        FailureDecision decision = explainer.explain("Task: mine iron. Step 1/2: navigate.");

        assertThat(decision.action()).isEqualTo(FailureDecision.RETRY);
        assertThat(decision.explanation()).contains("transient timeout");
        assertThat(decision.revisedPlan()).isNull();
        assertThat(decision.isRetry()).isTrue();
        assertThat(decision.isRevise()).isFalse();
        assertThat(decision.isAbandon()).isFalse();
    }

    @Test
    void explain_deserialisesAbandonDecision() {
        String json = """
                {
                  "explanation": "No diamonds exist at Y=64 in this biome.",
                  "action": "ABANDON",
                  "revisedPlan": null
                }
                """;

        PlanFailureExplainer explainer = explainerWith(fakeModelReturning(json));
        FailureDecision decision = explainer.explain("Task: mine diamonds. Step 1/1: mine diamond_ore.");

        assertThat(decision.action()).isEqualTo(FailureDecision.ABANDON);
        assertThat(decision.explanation()).contains("diamonds");
        assertThat(decision.revisedPlan()).isNull();
        assertThat(decision.isAbandon()).isTrue();
        assertThat(decision.isRetry()).isFalse();
        assertThat(decision.isRevise()).isFalse();
    }

    @Test
    void explain_deserialisesReviseDecision_withRevisedPlan() {
        String json = """
                {
                  "explanation": "Wrong coordinates — navigating to mine shaft entrance first.",
                  "action": "REVISE",
                  "revisedPlan": {
                    "summary": "Navigate to mine shaft then mine iron",
                    "steps": [
                      {"type":"Navigate","x":-50,"y":32,"z":100,"timeoutSeconds":120},
                      {"type":"Mine","blockType":"minecraft:iron_ore","quantity":8,"searchRadius":32,"timeoutSeconds":1800}
                    ]
                  }
                }
                """;

        PlanFailureExplainer explainer = explainerWith(fakeModelReturning(json));
        FailureDecision decision = explainer.explain("Task: mine iron. Step 1/1: mine at wrong coords.");

        assertThat(decision.action()).isEqualTo(FailureDecision.REVISE);
        assertThat(decision.explanation()).contains("mine shaft");
        assertThat(decision.revisedPlan()).isNotNull();
        assertThat(decision.revisedPlan()).containsKey("steps");
        assertThat(decision.revisedPlan()).containsKey("summary");
        assertThat(decision.isRevise()).isTrue();
        assertThat(decision.isRetry()).isFalse();
        assertThat(decision.isAbandon()).isFalse();
    }

    @Test
    void explain_revisedPlanSteps_containsExpectedEntries() {
        String json = """
                {
                  "explanation": "Must navigate before mining.",
                  "action": "REVISE",
                  "revisedPlan": {
                    "summary": "Go then mine",
                    "steps": [
                      {"type":"Navigate","x":10,"y":64,"z":20,"timeoutSeconds":60}
                    ]
                  }
                }
                """;

        PlanFailureExplainer explainer = explainerWith(fakeModelReturning(json));
        FailureDecision decision = explainer.explain("context");

        @SuppressWarnings("unchecked")
        var steps = (java.util.List<java.util.Map<String, Object>>) decision.revisedPlan().get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("type", "Navigate");
        assertThat(steps.get(0)).containsEntry("x", 10);
    }
}
