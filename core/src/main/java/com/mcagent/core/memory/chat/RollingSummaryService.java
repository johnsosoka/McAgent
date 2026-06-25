package com.mcagent.core.memory.chat;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Summarizes a batch of evicted chat messages into a concise rolling summary.
 *
 * <p>Called by {@link LayeredChatMemory} when the pending-summary buffer
 * reaches the configured threshold. The summary is injected back into
 * memory as the second message (after the pinned system message) so the
 * LLM retains essential context across long sessions without consuming
 * the entire working window.</p>
 *
 * <p>Uses the same {@link ChatModel} bean that drives the assistant, but
 * with a focused summarization prompt. The call is synchronous and is made
 * while holding the {@code LayeredChatMemory} intrinsic lock — keep the
 * prompt short so latency stays acceptable.</p>
 */
@Slf4j
public class RollingSummaryService {

    static final String SUMMARIZE_PROMPT_TEMPLATE =
            "Summarize the following Minecraft bot conversation history in <= 3 sentences, "
            + "preserving any active goals, locations, and player constraints:\n\n{messages}";

    private final ChatModel chatModel;
    private final int maxPriorSummaryChars;

    /**
     * @param chatModel            the chat model used to generate summaries
     * @param maxPriorSummaryChars maximum character length of the prior summary passed
     *                             into the summarization prompt; if exceeded, the prior
     *                             summary is truncated to the last N characters
     */
    public RollingSummaryService(ChatModel chatModel, int maxPriorSummaryChars) {
        this.chatModel = chatModel;
        this.maxPriorSummaryChars = maxPriorSummaryChars;
    }

    /**
     * Produce an updated rolling summary by merging the prior summary with
     * the newly evicted messages.
     *
     * <p>If {@code priorSummary} exceeds {@code maxPriorSummaryChars}, it is
     * truncated to the last {@code maxPriorSummaryChars} characters (with a
     * leading {@code "…"} ellipsis) before being included in the prompt. This
     * prevents the summarization context from growing without bound across many
     * cycles.</p>
     *
     * @param priorSummary the existing rolling summary (may be blank on the first call)
     * @param evictedMessages messages that were evicted from the working window
     * @return the updated summary string; never null, never blank if inputs have content
     */
    public String summarize(String priorSummary, List<ChatMessage> evictedMessages) {
        String effectivePrior = truncateIfNeeded(priorSummary);
        String evictedText = renderMessages(evictedMessages);
        String fullContext = buildContext(effectivePrior, evictedText);

        String prompt = SUMMARIZE_PROMPT_TEMPLATE.replace("{messages}", fullContext);
        log.debug("Requesting rolling summary ({} evicted messages)", evictedMessages.size());

        try {
            String newSummary = chatModel.chat(prompt);
            log.debug("Rolling summary updated ({} chars)", newSummary == null ? 0 : newSummary.length());
            return newSummary != null ? newSummary.trim() : priorSummary;
        } catch (Exception e) {
            log.warn("Rolling summary call failed — retaining prior summary", e);
            return priorSummary;
        }
    }

    /**
     * Truncates {@code summary} to the last {@code maxPriorSummaryChars} characters
     * when it exceeds the cap, prepending a {@code "…"} ellipsis to signal truncation.
     * Returns the original value unchanged if it is within the limit.
     */
    String truncateIfNeeded(String summary) {
        if (summary == null || summary.length() <= maxPriorSummaryChars) {
            return summary;
        }
        String truncated = "…" + summary.substring(summary.length() - maxPriorSummaryChars);
        log.debug("Prior summary truncated from {} to {} chars (cap={})",
                summary.length(), truncated.length(), maxPriorSummaryChars);
        return truncated;
    }

    private static String buildContext(String priorSummary, String evictedText) {
        if (priorSummary == null || priorSummary.isBlank()) {
            return evictedText;
        }
        return "Prior summary:\n" + priorSummary + "\n\nNew messages:\n" + evictedText;
    }

    private static String renderMessages(List<ChatMessage> messages) {
        return messages.stream()
                .map(msg -> "[" + msg.type().name() + "] " + messageText(msg))
                .collect(Collectors.joining("\n"));
    }

    private static String messageText(ChatMessage msg) {
        return switch (msg) {
            case dev.langchain4j.data.message.UserMessage um -> um.singleText();
            case dev.langchain4j.data.message.AiMessage am -> am.text();
            case dev.langchain4j.data.message.SystemMessage sm -> sm.text();
            default -> msg.toString();
        };
    }
}
