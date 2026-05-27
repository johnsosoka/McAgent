package com.mcagent.core.service;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Central service that processes player input through the LLM pipeline.
 * Also manages framework context injection so Baritone/game status updates
 * appear in the LLM's conversation history as <framework> tagged messages.
 *
 * <p>Tools (navigateTo, sendMessage, etc.) are automatically invoked by LangChain4j
 * when the LLM decides to call them. The returned String is the LLM's final
 * conversational response.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LangChain4jService {

    private final Assistant assistant;
    private final ChatMemory chatMemory;
    private final BotOperations bot;

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
        try {
            injectPlayerContext(playerId);
            synchronized (chatMemory) {
                return assistant.chat(playerMessage);
            }
        } catch (Exception e) {
            log.error("LLM processing failed", e);
            return null;
        }
    }

    /**
     * Inject a framework status message into the LLM conversation memory.
     * The message is wrapped in &lt;framework&gt; tags so the system prompt
     * instructs the model to treat it as a system status update rather than
     * player input.
     *
     * @param message raw status text (e.g. "Arrived at destination.")
     */
    public void addFrameworkContext(String message) {
        String tagged = "<framework>" + message + "</framework>";
        log.debug("Injecting framework context: {}", tagged);
        synchronized (chatMemory) {
            chatMemory.add(UserMessage.from(tagged));
        }
    }

    /**
     * Process an urgent autonomous observation by injecting framework context
     * and immediately calling the LLM without waiting for player input.
     * Used in active observation mode when threats or opportunities are detected.
     *
     * @param observation the autonomous observation text
     * @return the LLM's response, or null on error
     */
    public String processUrgentObservation(String observation) {
        log.debug("Processing urgent observation: {}", observation);
        try {
            addFrameworkContext(observation);
            synchronized (chatMemory) {
                // The observation is already in memory as a framework message.
                // The synthetic prompt nudges the model to react without waiting for player input.
                return assistant.chat("Autonomous observation: " + observation);
            }
        } catch (Exception e) {
            log.error("Urgent observation processing failed", e);
            return null;
        }
    }

    private void injectPlayerContext(String playerId) {
        var pos = bot.getPlayerPosition(playerId);
        String context = pos
                .map(loc -> "<player_context>Current player: " + playerId + " at " + loc + "</player_context>")
                .orElse("<player_context>Current player: " + playerId + " (position unknown — may be out of range)</player_context>");
        log.debug("Injecting player context: {}", context);
        synchronized (chatMemory) {
            chatMemory.add(UserMessage.from(context));
        }
    }
}
