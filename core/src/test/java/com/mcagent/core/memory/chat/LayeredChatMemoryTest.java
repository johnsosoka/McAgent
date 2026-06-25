package com.mcagent.core.memory.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LayeredChatMemory}.
 *
 * <p>All tests use a {@link FakeChatModel} to avoid hitting the real LLM.
 * The FakeChatModel records how many times summarization was invoked and
 * returns predictable output so assertions are deterministic.</p>
 */
class LayeredChatMemoryTest {

    /**
     * Minimal ChatModel stub that returns a canned summary and records call count.
     */
    static class FakeChatModel implements ChatModel {

        private final AtomicInteger callCount = new AtomicInteger(0);
        private final String summaryText;

        FakeChatModel(String summaryText) {
            this.summaryText = summaryText;
        }

        @Override
        public String chat(String userMessage) {
            callCount.incrementAndGet();
            return summaryText;
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    private static final int WORKING_WINDOW = 3;
    private static final int SUMMARY_TRIGGER = 2;

    private FakeChatModel fakeChatModel;
    private RollingSummaryService summaryService;
    private LayeredChatMemory memory;

    @BeforeEach
    void setUp() {
        fakeChatModel = new FakeChatModel("Prior context: player was mining iron ore.");
        summaryService = new RollingSummaryService(fakeChatModel, 800);
        memory = new LayeredChatMemory(WORKING_WINDOW, SUMMARY_TRIGGER, summaryService);
    }

    // -------------------------------------------------------------------------
    // Working window eviction
    // -------------------------------------------------------------------------

    @Test
    void workingWindow_retainsAtMostN_messages() {
        addUser("msg-1");
        addUser("msg-2");
        addUser("msg-3");
        addUser("msg-4"); // should evict msg-1

        List<ChatMessage> msgs = memory.messages();
        // Working window has 3; we do not count summary or pin here
        long userMessages = msgs.stream()
                .filter(m -> m.type() == ChatMessageType.USER)
                .filter(m -> !((UserMessage) m).singleText().startsWith("<summary>"))
                .count();
        assertThat(userMessages).isLessThanOrEqualTo(WORKING_WINDOW);
    }

    @Test
    void workingWindow_evictsOldestFirst() {
        // Fill window and trigger one eviction
        addUser("alpha");
        addUser("beta");
        addUser("gamma");
        addUser("delta"); // evicts alpha

        List<String> texts = userMessageTexts(memory.messages());
        assertThat(texts).doesNotContain("alpha");
        assertThat(texts).contains("beta", "gamma", "delta");
    }

    @Test
    void workingWindow_maintainsFifoOrder() {
        addUser("first");
        addUser("second");
        addUser("third");

        List<String> texts = userMessageTexts(memory.messages());
        assertThat(texts).containsExactly("first", "second", "third");
    }

    // -------------------------------------------------------------------------
    // Pinned system message
    // -------------------------------------------------------------------------

    @Test
    void pinnedSystemMessage_survivesFull_eviction() {
        memory.setPinnedSystem("Active goal: mine 500 iron ore at Y=-32");

        // Fill past the working window to force evictions
        for (int i = 0; i < WORKING_WINDOW + SUMMARY_TRIGGER + 2; i++) {
            addUser("msg-" + i);
        }

        List<ChatMessage> msgs = memory.messages();
        assertThat(msgs).isNotEmpty();
        assertThat(msgs.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) msgs.get(0)).text()).isEqualTo("Active goal: mine 500 iron ore at Y=-32");
    }

    @Test
    void pinnedSystemMessage_isAtIndexZero() {
        memory.setPinnedSystem("My goal");
        addUser("some message");

        List<ChatMessage> msgs = memory.messages();
        assertThat(msgs.get(0)).isInstanceOf(SystemMessage.class);
    }

    @Test
    void pinnedSystemMessage_canBeCleared() {
        memory.setPinnedSystem("goal");
        memory.setPinnedSystem(null);

        List<ChatMessage> msgs = memory.messages();
        assertThat(msgs).noneMatch(m -> m.type() == ChatMessageType.SYSTEM);
    }

    @Test
    void noPinnedMessage_byDefault() {
        addUser("hello");
        List<ChatMessage> msgs = memory.messages();
        assertThat(msgs).noneMatch(m -> m.type() == ChatMessageType.SYSTEM);
    }

    // -------------------------------------------------------------------------
    // Rolling summary
    // -------------------------------------------------------------------------

    @Test
    void summaryMessage_isAtIndexOne_whenPinIsPresent() {
        memory.setPinnedSystem("My goal");

        // Cause enough evictions to trigger summarization
        // summaryTrigger=2, workingWindow=3 → need workingWindow+summaryTrigger messages
        for (int i = 0; i < WORKING_WINDOW + SUMMARY_TRIGGER; i++) {
            addUser("msg-" + i);
        }

        // Ensure summary was generated
        assertThat(fakeChatModel.getCallCount()).isGreaterThan(0);

        List<ChatMessage> msgs = memory.messages();
        // msgs[0] = pinned system, msgs[1] = summary
        assertThat(msgs.size()).isGreaterThanOrEqualTo(2);
        assertThat(msgs.get(0)).isInstanceOf(SystemMessage.class);
        UserMessage summaryMsg = (UserMessage) msgs.get(1);
        assertThat(summaryMsg.singleText()).startsWith("<summary>");
        assertThat(summaryMsg.singleText()).endsWith("</summary>");
    }

    @Test
    void summaryMessage_isAtIndexZero_whenNoPinPresent() {
        // No pin — summary should appear at index 0
        for (int i = 0; i < WORKING_WINDOW + SUMMARY_TRIGGER; i++) {
            addUser("msg-" + i);
        }

        assertThat(fakeChatModel.getCallCount()).isGreaterThan(0);

        List<ChatMessage> msgs = memory.messages();
        assertThat(msgs.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) msgs.get(0)).singleText()).startsWith("<summary>");
    }

    @Test
    void summarization_triggeredAt_summaryTriggerThreshold() {
        // Each message beyond workingWindow becomes a pending eviction.
        // We need SUMMARY_TRIGGER evictions to fire.
        // Total messages = workingWindow + summaryTrigger
        int totalMessages = WORKING_WINDOW + SUMMARY_TRIGGER;

        for (int i = 0; i < totalMessages; i++) {
            addUser("msg-" + i);
        }

        assertThat(fakeChatModel.getCallCount()).isEqualTo(1);
        assertThat(memory.getRollingSummary()).isNotNull();
        assertThat(memory.getPendingBufferSize()).isZero();
    }

    @Test
    void summarization_notTriggered_beforeThreshold() {
        // Add exactly workingWindow + (summaryTrigger - 1) messages
        // to produce summaryTrigger-1 evictions — should NOT trigger
        int totalMessages = WORKING_WINDOW + SUMMARY_TRIGGER - 1;
        for (int i = 0; i < totalMessages; i++) {
            addUser("msg-" + i);
        }

        assertThat(fakeChatModel.getCallCount()).isZero();
        assertThat(memory.getRollingSummary()).isNull();
        assertThat(memory.getPendingBufferSize()).isEqualTo(SUMMARY_TRIGGER - 1);
    }

    // -------------------------------------------------------------------------
    // messages() ordering contract
    // -------------------------------------------------------------------------

    @Test
    void messages_returnsCorrectOrder_pinSummaryWorking() {
        memory.setPinnedSystem("goal");
        // Trigger a summary
        for (int i = 0; i < WORKING_WINDOW + SUMMARY_TRIGGER; i++) {
            addUser("m" + i);
        }

        List<ChatMessage> msgs = memory.messages();
        // Verify structural order
        assertThat(msgs.get(0)).isInstanceOf(SystemMessage.class);                                   // pin
        assertThat(((UserMessage) msgs.get(1)).singleText()).startsWith("<summary>");                 // summary
        // Remaining messages are the working window (non-summary user messages)
        for (int i = 2; i < msgs.size(); i++) {
            String text = ((UserMessage) msgs.get(i)).singleText();
            assertThat(text).doesNotStartWith("<summary>");
        }
    }

    @Test
    void messages_isEmpty_afterClear() {
        addUser("something");
        memory.setPinnedSystem("goal");
        memory.clear();

        assertThat(memory.messages()).isEmpty();
        assertThat(memory.getPinnedSystem()).isNull();
        assertThat(memory.getRollingSummary()).isNull();
    }

    // -------------------------------------------------------------------------
    // Validation / edge cases
    // -------------------------------------------------------------------------

    @Test
    void constructor_rejectsInvalid_workingWindow() {
        assertThatThrownBy(() -> new LayeredChatMemory(0, 8, summaryService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workingWindow");
    }

    @Test
    void constructor_rejectsInvalid_summaryTrigger() {
        assertThatThrownBy(() -> new LayeredChatMemory(12, 0, summaryService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summaryTrigger");
    }

    @Test
    void id_isStable() {
        assertThat(memory.id()).isEqualTo(memory.id());
        assertThat(memory.id()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void addUser(String text) {
        memory.add(UserMessage.from(text));
    }

    private List<String> userMessageTexts(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.type() == ChatMessageType.USER)
                .filter(m -> !((UserMessage) m).singleText().startsWith("<summary>"))
                .map(m -> ((UserMessage) m).singleText())
                .toList();
    }
}
