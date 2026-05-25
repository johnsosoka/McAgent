package com.mcagent.fabric;

import com.mcagent.fabric.queue.BotEventQueue;
import com.mcagent.fabric.queue.EventPriority;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles incoming Minecraft chat messages and enqueues them for LLM processing.
 * Replaces the Forge ChatEventHandler with Fabric event registration.
 */
@Slf4j
@RequiredArgsConstructor
public class FabricChatHandler {

    private final BotEventQueue botEventQueue;

    private static final Pattern CHAT_PATTERN = Pattern.compile("^<(\\w+)>\\s*(.+)");
    private static final Pattern ALTERNATIVE_PATTERN = Pattern.compile("^(\\w+):\\s*(.+)");

    // Triggers sorted by length descending so longer triggers match first
    private static final String[] TRIGGERS = Arrays.stream(new String[]{"hey bot", "mcagent", "agent", "bot"})
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toArray(String[]::new);

    public void onChatMessage(String message) {
        // Ignore system/broadcast messages (including our own /say output)
        if (message.startsWith("[")) {
            return;
        }

        String playerName = extractPlayerName(message);
        String command = extractCommand(message);

        if (command == null) {
            return; // Not directed at bot
        }

        // Ignore messages sent by the local player (the bot itself)
        String localPlayer = getLocalPlayerName();
        if (localPlayer != null && localPlayer.equalsIgnoreCase(playerName)) {
            return;
        }

        EventPriority priority = resolvePriority(command);
        botEventQueue.enqueueCommand(playerName, command, priority);
    }

    /**
     * Resolve command priority. Cancel/stop commands are treated as high priority.
     */
    private EventPriority resolvePriority(String command) {
        String lower = command.toLowerCase();
        if (lower.contains("cancel") || lower.contains("stop") || lower.contains("halt")) {
            return EventPriority.CANCEL;
        }
        return EventPriority.NORMAL;
    }

    /**
     * Shut down the chat handler. The lifecycle owner (McAgentFabricMod) is
     * responsible for shutting down the underlying {@link BotEventQueue}.
     */
    public void shutdown() {
        // No-op: queue lifecycle is managed by McAgentFabricMod to avoid double-shutdown.
    }

    /**
     * Check if a chat message is directed at the bot and extract the command text.
     */
    private String extractCommand(String message) {
        String lower = message.toLowerCase();
        for (String trigger : TRIGGERS) {
            int idx = lower.indexOf(trigger);
            if (idx >= 0) {
                int end = idx + trigger.length();
                String remainder = message.substring(end).trim();
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
        Matcher m = CHAT_PATTERN.matcher(message);
        if (m.matches()) {
            return m.group(1);
        }
        m = ALTERNATIVE_PATTERN.matcher(message);
        if (m.matches()) {
            return m.group(1);
        }
        return "unknown";
    }

    private String getLocalPlayerName() {
        var player = Minecraft.getInstance().player;
        return player != null ? player.getName().getString() : null;
    }
}
