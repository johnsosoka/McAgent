package com.mcagent.fabric;

import com.mcagent.fabric.observer.AutonomousObserver;
import com.mcagent.fabric.queue.BotEventQueue;
import com.mcagent.fabric.queue.EventPriority;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles incoming Minecraft chat messages and enqueues them for LLM processing.
 * Replaces the Forge ChatEventHandler with Fabric event registration.
 */
public class FabricChatHandler {
    private static final Logger log = LoggerFactory.getLogger(FabricChatHandler.class);

    public FabricChatHandler(final BotEventQueue botEventQueue, final AutonomousObserver autonomousObserver) {
        this.botEventQueue = botEventQueue;
        this.autonomousObserver = autonomousObserver;
    }


    private final BotEventQueue botEventQueue;
    private final AutonomousObserver autonomousObserver;

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

        if (handleToggleCommand(command)) {
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
     * Detect observer toggle commands and handle them immediately without
     * enqueuing to the LLM pipeline.
     */
    private boolean handleToggleCommand(String command) {
        String lower = command.toLowerCase();
        if (lower.equals("watch") || lower.equals("start watching") || lower.equals("observe")) {
            autonomousObserver.setEnabled(true);
            FabricChatSender.send("Observation enabled.");
            return true;
        }
        if (lower.equals("stop watching") || lower.equals("stop observing") || lower.equals("disable observation")) {
            autonomousObserver.setEnabled(false);
            FabricChatSender.send("Observation disabled.");
            return true;
        }
        if (lower.equals("passive mode") || lower.equals("set passive")) {
            autonomousObserver.setMode("passive");
            FabricChatSender.send("Observation mode set to passive.");
            return true;
        }
        if (lower.equals("active mode") || lower.equals("set active")) {
            autonomousObserver.setMode("active");
            FabricChatSender.send("Observation mode set to active. I'll act on threats automatically.");
            return true;
        }
        return false;
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
