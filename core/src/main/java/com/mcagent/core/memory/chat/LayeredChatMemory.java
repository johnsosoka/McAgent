package com.mcagent.core.memory.chat;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A tiered chat memory that keeps context coherent across long autonomous sessions.
 *
 * <h3>Structure</h3>
 * When {@link #messages()} is called, it assembles a snapshot in this order:
 * <ol>
 *   <li><strong>Pinned system message</strong> (optional) — set via
 *       {@link #setPinnedSystem(String)}. Never evicted. Intended for Sprint 13's
 *       TaskExecutor to inject the current active goal / plan.</li>
 *   <li><strong>Rolling summary</strong> (optional) — a {@link UserMessage} tagged
 *       {@code <summary>…</summary>}. Replaces the oldest evicted messages once the
 *       pending-summary buffer reaches the {@code summaryTrigger} threshold.</li>
 *   <li><strong>Working window</strong> — the N most-recent non-pinned messages,
 *       where N is {@code workingWindow}.</li>
 * </ol>
 *
 * <h3>Eviction flow</h3>
 * When a new message is added and the working window is already full, the oldest
 * entry is removed from the front of the deque and appended to the
 * <em>pending-summary buffer</em>. When that buffer accumulates
 * {@code summaryTrigger} entries, {@link RollingSummaryService#summarize} is called
 * and the buffer is cleared; the resulting text is stored as the rolling summary.
 *
 * <h3>Thread safety</h3>
 * All public methods are {@code synchronized} on {@code this}. The lock is the same
 * object that {@link com.mcagent.core.service.LangChain4jService} uses for its
 * {@code synchronized (chatMemory)} blocks, ensuring atomic read-modify-write sequences
 * in the urgent-observation path.
 */
@Slf4j
public class LayeredChatMemory implements ChatMemory {

    private static final String SUMMARY_OPEN = "<summary>";
    private static final String SUMMARY_CLOSE = "</summary>";

    private final int workingWindow;
    private final int summaryTrigger;
    private final RollingSummaryService summaryService;

    /** The pinned system message slot; null when not set. */
    private String pinnedSystemContent;

    /** FIFO working window. Front = oldest, back = newest. */
    private final Deque<ChatMessage> workingDeque;

    /** Messages evicted from the working window, awaiting summarization. */
    private final List<ChatMessage> pendingSummaryBuffer;

    /** The current rolling summary text; null when no summary exists yet. */
    private String rollingSummary;

    public LayeredChatMemory(int workingWindow, int summaryTrigger, RollingSummaryService summaryService) {
        if (workingWindow < 1) {
            throw new IllegalArgumentException("workingWindow must be >= 1, got " + workingWindow);
        }
        if (summaryTrigger < 1) {
            throw new IllegalArgumentException("summaryTrigger must be >= 1, got " + summaryTrigger);
        }
        this.workingWindow = workingWindow;
        this.summaryTrigger = summaryTrigger;
        this.summaryService = summaryService;
        this.workingDeque = new ArrayDeque<>(workingWindow + 1);
        this.pendingSummaryBuffer = new ArrayList<>(summaryTrigger + 1);
    }

    // -------------------------------------------------------------------------
    // ChatMemory contract
    // -------------------------------------------------------------------------

    /**
     * A stable, human-readable identifier for this memory instance.
     * LangChain4j uses this value for logging and for the per-memory store key.
     */
    @Override
    public synchronized Object id() {
        return "layered-chat-memory";
    }

    /**
     * Add a message to the working window, evicting the oldest message if full.
     * Evicted messages accumulate in the pending-summary buffer; once the buffer
     * reaches {@code summaryTrigger} entries the rolling summary is updated.
     */
    @Override
    public synchronized void add(ChatMessage message) {
        if (workingDeque.size() >= workingWindow) {
            ChatMessage evicted = workingDeque.pollFirst();
            if (evicted != null) {
                pendingSummaryBuffer.add(evicted);
                log.debug("Evicted message from working window (pending buffer size={})", pendingSummaryBuffer.size());
            }
        }
        workingDeque.addLast(message);

        if (pendingSummaryBuffer.size() >= summaryTrigger) {
            flushPendingToSummary();
        }
    }

    /**
     * Returns a consistent snapshot of all messages in the correct order:
     * [pinned system], [rolling summary], [working window oldest→newest].
     */
    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>(workingDeque.size() + 2);

        if (pinnedSystemContent != null) {
            result.add(SystemMessage.from(pinnedSystemContent));
        }

        if (rollingSummary != null && !rollingSummary.isBlank()) {
            result.add(UserMessage.from(SUMMARY_OPEN + rollingSummary + SUMMARY_CLOSE));
        }

        result.addAll(workingDeque);
        return result;
    }

    /**
     * Clears all messages, the rolling summary, and the pending buffer.
     * The pinned system message is also cleared.
     */
    @Override
    public synchronized void clear() {
        workingDeque.clear();
        pendingSummaryBuffer.clear();
        rollingSummary = null;
        pinnedSystemContent = null;
        log.debug("LayeredChatMemory cleared");
    }

    // -------------------------------------------------------------------------
    // Extended API
    // -------------------------------------------------------------------------

    /**
     * Set or replace the pinned system message content.
     * Pass {@code null} to remove the pin (no system message will be emitted).
     *
     * <p>This hook is reserved for Sprint 13's TaskExecutor to inject the current
     * active goal / plan text so it is always at position 0 of the context window
     * and survives normal eviction.</p>
     *
     * @param content the system message text, or null to clear
     */
    public synchronized void setPinnedSystem(String content) {
        this.pinnedSystemContent = content;
        log.debug("Pinned system message {}", content == null ? "cleared" : "updated");
    }

    /**
     * Returns the current pinned system message content, or null if none is set.
     */
    public synchronized String getPinnedSystem() {
        return pinnedSystemContent;
    }

    /**
     * Returns the current rolling summary text, or null if no summary has been
     * generated yet. Exposed for testing and diagnostic introspection.
     */
    public synchronized String getRollingSummary() {
        return rollingSummary;
    }

    /**
     * Returns the number of messages currently held in the pending-summary buffer.
     * Exposed for testing.
     */
    public synchronized int getPendingBufferSize() {
        return pendingSummaryBuffer.size();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Flushes the pending-summary buffer to the rolling summary via
     * {@link RollingSummaryService}. Clears the buffer on success; on failure,
     * discards the batch and logs a warning to prevent unbounded growth.
     *
     * <p>Caller must hold the monitor of this LayeredChatMemory instance.
     */
    private void flushPendingToSummary() {
        assert Thread.holdsLock(this) : "flushPendingToSummary must be called with this monitor held";
        log.debug("Pending buffer reached threshold ({}); requesting rolling summary", pendingSummaryBuffer.size());
        List<ChatMessage> batch = List.copyOf(pendingSummaryBuffer);
        pendingSummaryBuffer.clear();
        try {
            rollingSummary = summaryService.summarize(rollingSummary, batch);
            log.debug("Rolling summary updated successfully");
        } catch (Exception e) {
            log.warn("Summary flush failed — pending messages discarded to prevent unbounded growth", e);
        }
    }
}
