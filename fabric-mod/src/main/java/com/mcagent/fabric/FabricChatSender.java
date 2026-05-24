package com.mcagent.fabric;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe utility for sending chat messages from the Minecraft client.
 * All sends are scheduled on the main client thread to avoid data races.
 */
public final class FabricChatSender {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_agent");
    private static final int MAX_MESSAGE_LENGTH = 250;

    private FabricChatSender() {
        // utility class
    }

    /**
     * Send a message to Minecraft chat as the bot, safely from any thread.
     */
    public static void send(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String truncated = message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH) + "..."
                : message;

        Minecraft client = Minecraft.getInstance();
        client.execute(() -> sendOnClientThread(client, truncated));
    }

    private static void sendOnClientThread(Minecraft client, String message) {
        if (client.player == null || client.player.connection == null) {
            LOGGER.warn("Cannot send chat message: not connected");
            return;
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
