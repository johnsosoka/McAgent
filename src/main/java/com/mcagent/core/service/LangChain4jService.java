package com.mcagent.core.service;

import com.mcagent.core.model.BotResponse;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Central service that processes player input through the LLM pipeline.
 * Also manages framework context injection so Baritone/game status updates
 * appear in the LLM's conversation history as <framework> tagged messages.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LangChain4jService {

    private final Assistant assistant;
    private final ChatMemory chatMemory;

    public BotResponse processInput(String playerMessage, String playerId) {
        log.debug("Processing input from {}: {}", playerId, playerMessage);
        try {
            return assistant.chat(playerMessage);
        } catch (Exception e) {
            log.error("LLM processing failed", e);
            return BotResponse.builder()
                    .message("Sorry, I'm having trouble thinking right now. Error: " + e.getMessage())
                    .confidence(BotResponse.Confidence.LOW)
                    .build();
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
        chatMemory.add(UserMessage.from(tagged));
    }
}
