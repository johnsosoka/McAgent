package com.mcagent.core.memory.chat;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RollingSummaryService}.
 *
 * <p>Uses a capturing stub for {@link ChatModel} to inspect the prompt
 * that was sent to the model without making real network calls.</p>
 */
class RollingSummaryServiceTest {

    /**
     * ChatModel stub that captures the last prompt it received and returns
     * a predictable summary string.
     */
    static class CapturingChatModel implements ChatModel {
        String lastPrompt;

        @Override
        public String chat(String userMessage) {
            this.lastPrompt = userMessage;
            return "captured summary";
        }
    }

    @Test
    void priorSummaryIsTruncatedWhenTooLong() {
        int cap = 800;
        CapturingChatModel model = new CapturingChatModel();
        RollingSummaryService service = new RollingSummaryService(model, cap);

        String longPriorSummary = "x".repeat(2000);
        List<dev.langchain4j.data.message.ChatMessage> evicted =
                List.of(UserMessage.from("something happened"));

        service.summarize(longPriorSummary, evicted);

        // The prompt sent to the model must contain a truncated prior summary.
        // The truncated portion is at most cap chars + 1 for the ellipsis char.
        assertThat(model.lastPrompt).isNotNull();

        // Extract the prior summary segment from the prompt (between "Prior summary:\n" and "\n\nNew messages:")
        String marker = "Prior summary:\n";
        int start = model.lastPrompt.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = model.lastPrompt.indexOf("\n\nNew messages:", start);
        String priorSegment = model.lastPrompt.substring(start + marker.length(), end);

        // The ellipsis is a single UTF-8 multi-byte char (…), so length is cap + 1
        assertThat(priorSegment.length()).isLessThanOrEqualTo(cap + 1);
        assertThat(priorSegment).startsWith("…");
    }

    @Test
    void priorSummaryWithinLimit_isPassedUnchanged() {
        int cap = 800;
        CapturingChatModel model = new CapturingChatModel();
        RollingSummaryService service = new RollingSummaryService(model, cap);

        String shortSummary = "short summary";
        List<dev.langchain4j.data.message.ChatMessage> evicted =
                List.of(UserMessage.from("next event"));

        service.summarize(shortSummary, evicted);

        assertThat(model.lastPrompt).contains(shortSummary);
    }

    @Test
    void truncateIfNeeded_nullInput_returnsNull() {
        RollingSummaryService service = new RollingSummaryService(new CapturingChatModel(), 100);
        assertThat(service.truncateIfNeeded(null)).isNull();
    }

    @Test
    void truncateIfNeeded_exactlyAtLimit_returnsUnchanged() {
        RollingSummaryService service = new RollingSummaryService(new CapturingChatModel(), 10);
        String input = "a".repeat(10);
        assertThat(service.truncateIfNeeded(input)).isEqualTo(input);
    }

    @Test
    void truncateIfNeeded_overLimit_truncatesToLastNChars() {
        RollingSummaryService service = new RollingSummaryService(new CapturingChatModel(), 5);
        String input = "abcdefghij"; // 10 chars
        String result = service.truncateIfNeeded(input);

        assertThat(result).startsWith("…");
        // Should end with the last 5 chars of input
        assertThat(result).endsWith("fghij");
        assertThat(result.length()).isEqualTo(6); // 1 ellipsis + 5 chars
    }
}
