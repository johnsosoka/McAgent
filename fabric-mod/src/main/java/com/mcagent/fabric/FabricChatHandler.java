package com.mcagent.fabric;

import com.mcagent.core.service.BotOperations;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.fabric.observer.AutonomousObserver;
import com.mcagent.fabric.queue.BotEventQueue;
import com.mcagent.fabric.queue.EventPriority;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles incoming Minecraft chat messages and enqueues them for LLM processing.
 *
 * <p>Some commands bypass the LLM entirely for sub-500ms response times:
 * <ul>
 *   <li>Observer toggles — {@code watch}, {@code passive mode}, etc.</li>
 *   <li>Hard-stop — {@code stop}, {@code cancel}, {@code halt}, {@code abort}.</li>
 *   <li>Status query — {@code status}, {@code what are you doing}.</li>
 * </ul>
 * </p>
 */
public class FabricChatHandler {
    private static final Logger log = LoggerFactory.getLogger(FabricChatHandler.class);

    private final BotEventQueue botEventQueue;
    private final AutonomousObserver autonomousObserver;
    private final BotOperations bot;
    private final JobTracker jobTracker;
    private final Counters counters;
    // Injectable for tests — defaults to the real static sender.
    final Consumer<String> chatSender;

    public FabricChatHandler(
            BotEventQueue botEventQueue,
            AutonomousObserver autonomousObserver,
            BotOperations bot,
            JobTracker jobTracker,
            Counters counters) {
        this(botEventQueue, autonomousObserver, bot, jobTracker, counters, FabricChatSender::send);
    }

    /**
     * Package-private constructor for testing — allows injection of a no-op or recording sender
     * to avoid loading Minecraft classes in a headless JVM.
     */
    FabricChatHandler(
            BotEventQueue botEventQueue,
            AutonomousObserver autonomousObserver,
            BotOperations bot,
            JobTracker jobTracker,
            Counters counters,
            Consumer<String> chatSender) {
        this.botEventQueue = botEventQueue;
        this.autonomousObserver = autonomousObserver;
        this.bot = bot;
        this.jobTracker = jobTracker;
        this.counters = counters;
        this.chatSender = chatSender;
    }

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
     * Detect bypass commands and handle them immediately without routing to the LLM.
     * Returns {@code true} if the command was consumed; {@code false} to proceed normally.
     *
     * <p>Package-private so unit tests can invoke it directly with a raw command string,
     * avoiding the Minecraft class-loading in {@link #onChatMessage}.</p>
     */
    boolean handleToggleCommand(String command) {
        String lower = command.toLowerCase().trim();

        // --- Hard-stop bypass ---
        if (lower.equals("stop") || lower.equals("cancel")
                || lower.equals("halt") || lower.equals("abort")) {
            bot.cancel();
            botEventQueue.clearInbound();
            jobTracker.clearJob();
            chatSender.accept("Stopped.");
            log.info("Hard-stop executed: Baritone cancelled, inbound queue cleared");
            return true;
        }

        // --- Status bypass ---
        if (lower.equals("status") || lower.equals("what are you doing")) {
            String status = buildStatusString();
            chatSender.accept(status);
            return true;
        }

        // --- Stats bypass (counter snapshot) ---
        if (lower.equals("stats")) {
            chatSender.accept(buildStatsString());
            return true;
        }

        // --- Observer toggles ---
        if (lower.equals("watch") || lower.equals("start watching") || lower.equals("observe")) {
            autonomousObserver.setEnabled(true);
            chatSender.accept("Observation enabled.");
            return true;
        }
        if (lower.equals("stop watching") || lower.equals("stop observing") || lower.equals("disable observation")) {
            autonomousObserver.setEnabled(false);
            chatSender.accept("Observation disabled.");
            return true;
        }
        if (lower.equals("passive mode") || lower.equals("set passive")) {
            autonomousObserver.setMode("passive");
            chatSender.accept("Observation mode set to passive.");
            return true;
        }
        if (lower.equals("active mode") || lower.equals("set active")) {
            autonomousObserver.setMode("active");
            chatSender.accept("Observation mode set to active. I'll act on threats automatically.");
            return true;
        }

        return false;
    }

    /**
     * Compose a compact status string without any LLM round-trip.
     * Format: {@code Mode: {mode} | Observer: {on/off} | Job: {desc} ({elapsed}) | Last: {progress}}
     */
    private String buildStatusString() {
        String mode = autonomousObserver.getMode();
        String observerState = autonomousObserver.isEnabled() ? "on" : "off";

        // Capture the Optional once to avoid a TOCTOU race where a concurrent clearJob()
        // call could make the second getCurrentJob() return empty after isPresent() returned true.
        Optional<JobTracker.Job> jobOpt = jobTracker.getCurrentJob();
        String jobPart = jobOpt
                .map(job -> job.description() + " (" + jobTracker.elapsedFormatted() + ")")
                .orElse("idle");

        String lastProgress = jobTracker.getLastProgress();
        String lastPart = lastProgress.isBlank() ? "—" : lastProgress;

        return "Mode: " + mode
                + " | Observer: " + observerState
                + " | Job: " + jobPart
                + " | Last: " + lastPart;
    }

    /**
     * Compose a compact counter-snapshot string for the {@code agent stats} bypass.
     *
     * <p>Shows a hand-picked set of counters covering inbound, outbound, LLM,
     * and framework dimensions. The result is truncated to 250 chars (the Minecraft
     * chat length limit after sanitization).</p>
     */
    private String buildStatsString() {
        // Hand-picked keys ordered for readability.
        String[] keys = {
            "inbound.enqueued", "inbound.dispatched", "inbound.dropped",
            "outbound.sent", "outbound.rate_limited", "outbound.dropped",
            "llm.calls", "llm.errors",
            "framework.published", "framework.dropped"
        };
        Map<String, Long> snap = counters.snapshot();
        String stats = Arrays.stream(keys)
                .map(k -> k + "=" + snap.getOrDefault(k, 0L))
                .collect(Collectors.joining(" | "));
        // Minecraft chat limit is 256; truncate safely.
        return stats.length() > 250 ? stats.substring(0, 250) : stats;
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
