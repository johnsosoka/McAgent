package com.mcagent.core.service;

import com.mcagent.core.memory.chat.LayeredChatMemory;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.RunLog;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Central service that processes player input through the LLM pipeline.
 * Also manages framework context injection so Baritone/game status updates
 * appear in the LLM's conversation history as {@code <framework>} tagged messages.
 *
 * <p>Tools (navigateTo, sendMessage, etc.) are automatically invoked by LangChain4j
 * when the LLM decides to call them. The returned String is the LLM's final
 * conversational response.</p>
 *
 * <h3>Thread safety</h3>
 * All paths that touch {@code chatMemory} or {@code assistant} hold the
 * {@code chatMemory} intrinsic lock. This ensures that the inbound dispatcher
 * and the urgent-observation dispatcher cannot interleave their memory writes
 * with each other's LLM call — preventing the double-injection and mixed-context
 * race described in CRITICAL-3 of the architectural review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LangChain4jService {

    private final Assistant assistant;
    private final ChatMemory chatMemory;
    private final BotOperations bot;
    private final Counters counters;
    private final RunLog runLog;

    /**
     * Process player input through the LLM.
     * Before the message is sent, a {@code <player_context>} tag is injected
     * into memory so the model knows who is speaking and where they are.
     * Tools are automatically executed by LangChain4j during the chat.
     *
     * @param playerMessage the message directed at the bot
     * @param playerId the player's name (for logging/memory context)
     * @return the LLM's conversational response, or null on error
     */
    public String processInput(String playerMessage, String playerId) {
        log.debug("Processing input from {}: {}", playerId, playerMessage);
        counters.inc("llm.calls");
        long start = System.currentTimeMillis();
        try {
            synchronized (chatMemory) {
                injectPlayerContextUnsafe(playerId);
                String response = assistant.chat(playerMessage);
                long durationMs = System.currentTimeMillis() - start;
                counters.inc("llm.latency.ms.sum", durationMs);
                counters.inc("llm.latency.ms.count");
                runLog.log("llm_call", Map.of(
                        "player", playerId,
                        "message_len", playerMessage.length(),
                        "duration_ms", durationMs));
                return response;
            }
        } catch (Exception e) {
            counters.inc("llm.errors");
            log.error("LLM processing failed", e);
            return null;
        }
    }

    /**
     * Inject a framework status message into the LLM conversation memory.
     * The message is wrapped in {@code <framework>} tags so the system prompt
     * instructs the model to treat it as a system status update rather than
     * player input.
     *
     * @param message raw status text (e.g. "Arrived at destination.")
     */
    public void addFrameworkContext(String message) {
        log.debug("Injecting framework context: {}", message);
        synchronized (chatMemory) {
            addFrameworkContextUnsafe(message);
        }
    }

    /**
     * Process an urgent autonomous observation atomically: injects the framework
     * context and calls the LLM in a single synchronized block so the inbound
     * dispatcher cannot race between the two steps.
     *
     * <p>The observation is added to memory as a {@code <framework>} message.
     * The chat prompt then nudges the model to react without re-stating the full
     * observation text, avoiding the double-injection bug from CRITICAL-1/CRITICAL-3.</p>
     *
     * @param observation the autonomous observation text
     * @return the LLM's response, or null on error
     */
    public String processUrgentObservation(String observation) {
        log.debug("Processing urgent observation: {}", observation);
        counters.inc("llm.urgent.calls");
        long start = System.currentTimeMillis();
        try {
            synchronized (chatMemory) {
                addFrameworkContextUnsafe(observation);
                String response = assistant.chat("React to the latest framework observation.");
                long durationMs = System.currentTimeMillis() - start;
                counters.inc("llm.urgent.latency.ms.sum", durationMs);
                counters.inc("llm.urgent.latency.ms.count");
                runLog.log("llm_urgent_call", Map.of(
                        "observation_len", observation.length(),
                        "duration_ms", durationMs));
                return response;
            }
        } catch (Exception e) {
            counters.inc("llm.urgent.errors");
            log.error("Urgent observation processing failed", e);
            return null;
        }
    }

    /**
     * Set or clear the pinned system message on the underlying {@link LayeredChatMemory}.
     * If the chat memory is not a {@link LayeredChatMemory}, this is a no-op (safe to call
     * in test contexts where a mock or simpler memory is injected).
     *
     * <p>Intended for Sprint 13's TaskExecutor, which will inject the current active goal
     * so it appears at position 0 of every LLM context window and survives eviction.</p>
     *
     * @param content the system message text, or null to clear the pin
     */
    public void setPinnedSystem(String content) {
        if (chatMemory instanceof LayeredChatMemory layered) {
            synchronized (chatMemory) {
                layered.setPinnedSystem(content);
            }
        } else {
            log.debug("setPinnedSystem called but chatMemory is not a LayeredChatMemory — no-op");
        }
    }

    // -------------------------------------------------------------------------
    // Unsafe helpers — callers MUST hold the chatMemory lock
    // -------------------------------------------------------------------------

    /**
     * Injects a {@code <framework>} tagged message into memory.
     * Callers must hold {@code synchronized (chatMemory)} before invoking.
     */
    void addFrameworkContextUnsafe(String message) {
        chatMemory.add(UserMessage.from("<framework>" + message + "</framework>"));
    }

    /**
     * Injects a {@code <player_context>} tagged message into memory.
     * Callers must hold {@code synchronized (chatMemory)} before invoking.
     */
    private void injectPlayerContextUnsafe(String playerId) {
        var pos = bot.getPlayerPosition(playerId);
        String context = pos
                .map(loc -> "<player_context>Current player: " + playerId + " at " + loc + "</player_context>")
                .orElse("<player_context>Current player: " + playerId + " (position unknown — may be out of range)</player_context>");
        log.debug("Injecting player context: {}", context);
        chatMemory.add(UserMessage.from(context));
    }
}
