package com.mcagent.fabric;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe utility for sending chat messages from the Minecraft client.
 * All sends are scheduled on the main client thread to avoid data races.
 * Rate-limited to prevent server spam kicks.
 */
public final class FabricChatSender {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_agent");
    private static final int MAX_MESSAGE_LENGTH = 250;
    private static final long MIN_INTERVAL_MS = 3000; // max 1 msg per 3s

    private static long lastSendTime = 0;

    private FabricChatSender() {
        // utility class
    }

    /**
     * Send a message to Minecraft chat as the bot, safely from any thread.
     * Rate-limited: messages sent faster than 1 per 3 seconds are dropped.
     * Illegal characters are stripped to prevent disconnects.
     */
    public static void send(String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastSendTime;
        if (elapsed < MIN_INTERVAL_MS) {
            LOGGER.debug("Chat send rate-limited ({} ms since last)", elapsed);
            return;
        }
        lastSendTime = now;

        String sanitized = sanitize(message);
        if (sanitized.isBlank()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        client.execute(() -> sendOnClientThread(client, sanitized));
    }

    /**
     * Strip characters that are illegal in Minecraft chat/commands:
     * newlines, backticks, control chars, and other problematic symbols.
     */
    private static String sanitize(String message) {
        return message
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replace('`', '\'')
                .replaceAll("[^\\x20-\\x7E]", "") // keep only printable ASCII
                .trim();
    }

    private static void sendOnClientThread(Minecraft client, String message) {
        if (client.player == null || client.player.connection == null) {
            LOGGER.warn("Cannot send chat message: not connected");
            return;
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "...";
        }

        if (message.startsWith("/")) {
            // User explicitly wants a command — strip leading slash and send raw
            client.player.connection.sendCommand(message.substring(1));
        } else {
            // Use /say command to bypass chat-signing chain (1.19+)
            // /say broadcasts to all players without breaking cryptographic chat chain
            client.player.connection.sendCommand("say " + message);
        }
    }
}
