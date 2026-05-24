package com.mcagent.mod.handler;

import com.mcagent.core.model.BotResponse;
import com.mcagent.core.service.LangChain4jService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles incoming Minecraft chat messages and delegates to the LLM service.
 */
@Slf4j
@RequiredArgsConstructor
public class ChatEventHandler {

    private final LangChain4jService langChainService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mc-agent-chat");
        t.setDaemon(true);
        return t;
    });

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        String message = event.getMessage().getString();
        String playerName = extractPlayerName(message);
        String command = extractCommand(message);

        if (command == null) {
            return; // Not directed at bot
        }

        executor.submit(() -> {
            try {
                log.info("Processing command from {}: {}", playerName, command);
                BotResponse response = langChainService.processInput(command, playerName);

                if (response != null && response.getMessage() != null) {
                    sendChatMessage(response.getMessage());
                }
            } catch (Exception e) {
                log.error("Error processing chat command", e);
                sendChatMessage("Sorry, something went wrong: " + e.getMessage());
            }
        });
    }

    /**
     * Check if a chat message is directed at the bot and extract the command text.
     */
    private String extractCommand(String message) {
        String lower = message.toLowerCase();
        String[] triggers = {"bot", "agent", "hey bot", "mcagent"};

        for (String trigger : triggers) {
            if (lower.contains(trigger)) {
                // Return the message with the trigger removed, preserving original casing after trigger
                int idx = lower.indexOf(trigger);
                int end = idx + trigger.length();
                String remainder = message.substring(end).trim();
                // Remove common separators
                remainder = remainder.replaceFirst("^[,:;\\-\\s]+", "");
                return remainder.isEmpty() ? null : remainder;
            }
        }
        return null;
    }

    /**
     * Attempt to extract the player name from a chat message.
     * Minecraft chat format varies by server; this is a best-effort heuristic.
     */
    private String extractPlayerName(String message) {
        // Common formats: "<PlayerName> message" or "[Prefix] PlayerName: message"
        if (message.startsWith("<")) {
            int end = message.indexOf(">");
            if (end > 1) {
                return message.substring(1, end);
            }
        }
        // Fallback: unknown
        return "unknown";
    }

    private void sendChatMessage(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // Truncate to avoid chat kick for long messages
            String truncated = msg.length() > 250 ? msg.substring(0, 250) + "..." : msg;
            try {
                // Minecraft 1.20.1: send via player connection
                mc.player.connection.sendChat(truncated);
            } catch (Exception e1) {
                try {
                    // Fallback: try command packet
                    mc.player.connection.sendCommand(truncated);
                } catch (Exception e2) {
                    log.warn("Could not send chat message; logging locally: {}", truncated);
                    mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(truncated));
                }
            }
        }
    }
}
