package com.mcagent.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Bridge service that lets the LLM send chat messages to the player.
 * The actual transport (Minecraft chat, Discord, console) is provided
 * by the mod layer via {@link #setSender(Consumer)}.
 */
@Slf4j
@Component
public class ChatService {

    private Consumer<String> sender = msg -> log.debug("[ChatService] no sender wired: {}", msg);

    /**
     * Register the callback that delivers messages to the player.
     * Called once from the Forge mod entry point after Spring context refresh.
     */
    public void setSender(Consumer<String> sender) {
        this.sender = sender != null ? sender : msg -> {};
    }

    /**
     * Send a message to the player chat channel.
     */
    public void send(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String truncated = message.length() > 250 ? message.substring(0, 250) + "..." : message;
        sender.accept(truncated);
    }
}
