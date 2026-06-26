package com.mcagent.core.service;

import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: verifies the {@link Assistant} system prompt does not contain a
 * manual tool catalogue. LangChain4j auto-discovers {@code @Tool}-annotated methods;
 * a hand-maintained list becomes a drift hazard.
 */
class AssistantSystemPromptTest {

    @Test
    void systemPrompt_doesNotContainAvailableToolsBlock() throws Exception {
        String systemPrompt = Assistant.class
                .getMethod("chat", String.class)
                .getAnnotation(SystemMessage.class)
                .value()[0];

        assertThat(systemPrompt)
                .as("System prompt must not contain a manual <available_tools> catalogue")
                .doesNotContain("<available_tools>");
    }

    @Test
    void systemPrompt_containsVoiceGuidanceBlock() throws Exception {
        String systemPrompt = Assistant.class
                .getMethod("chat", String.class)
                .getAnnotation(SystemMessage.class)
                .value()[0];

        assertThat(systemPrompt)
                .as("System prompt must contain <voice_guidance> block")
                .contains("<voice_guidance>");
    }

    @Test
    void systemPrompt_containsDisambiguationGuidance() throws Exception {
        String systemPrompt = Assistant.class
                .getMethod("chat", String.class)
                .getAnnotation(SystemMessage.class)
                .value()[0];

        assertThat(systemPrompt)
                .as("System prompt must contain <disambiguation_guidance> block")
                .contains("<disambiguation_guidance>");
    }

    @Test
    void systemPrompt_disambiguationMentionsTwoQuestionMax() throws Exception {
        String systemPrompt = Assistant.class
                .getMethod("chat", String.class)
                .getAnnotation(SystemMessage.class)
                .value()[0];

        assertThat(systemPrompt)
                .as("System prompt must state the two-question maximum rule")
                .contains("Never ask more than 2");
    }
}
