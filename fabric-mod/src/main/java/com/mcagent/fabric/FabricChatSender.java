package com.mcagent.fabric;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe utility for sending chat messages from the Minecraft client.
 * All sends are scheduled on the main client thread to avoid data races.
 *
 * <p>Rate limit is configurable via {@link #setMinIntervalMs(long)}, defaulting to 1200ms —
 * Mojang's signed-chat hard floor is 1000ms; 1200ms is a safe margin.</p>
 *
 * <p>Rather than silently dropping rate-limited messages (old behaviour), {@link #send} now
 * returns a {@link SendResult} so callers can decide whether to retry.</p>
 */
public final class FabricChatSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricChatSender.class);
    private static final int MAX_MESSAGE_LENGTH = 250;

    /**
     * Default rate-limit interval (ms). Mojang's signed-chat enforcement floor is 1000ms;
     * 1200ms gives a safe margin above it.
     * <p>
     * Declared {@code volatile} so that the Spring-wired override from {@code BotProperties}
     * is visible to all threads without synchronisation overhead.
     */
    public static volatile long MIN_INTERVAL_MS = 1200;

    private static final AtomicLong lastSendTime = new AtomicLong(0);

    private FabricChatSender() {
        // utility class
    }

    /**
     * Override the rate-limit interval. Called once at Spring context startup from
     * {@code FabricModConfig} with the value from {@code bot.chat.min-interval-ms}.
     */
    public static void setMinIntervalMs(long ms) {
        MIN_INTERVAL_MS = ms;
    }

    /**
     * Possible outcomes of a {@link #send} call, allowing callers to react
     * appropriately (e.g. leave a rate-limited message at the head of the queue).
     */
    public enum SendResult {
        /** Message was dispatched to the client thread. */
        SENT,
        /** Message was skipped because not enough time has passed since the last send. */
        RATE_LIMITED,
        /** Message was null or blank — nothing to send. */
        EMPTY,
        /** Client is not currently connected; message cannot be sent. */
        DISCONNECTED
    }

    /**
     * Attempt to send a chat message. Returns a {@link SendResult} describing the outcome.
     * Rate-limit tracking uses a CAS loop so concurrent callers never produce a race.
     */
    public static SendResult send(String message) {
        if (message == null || message.isBlank()) {
            return SendResult.EMPTY;
        }

        long minInterval = MIN_INTERVAL_MS;
        long now = System.currentTimeMillis();
        long previous = lastSendTime.get();
        while (true) {
            if (now - previous < minInterval) {
                LOGGER.debug("Chat send rate-limited ({} ms since last)", now - previous);
                return SendResult.RATE_LIMITED;
            }
            if (lastSendTime.compareAndSet(previous, now)) {
                break;
            }
            // Another thread updated lastSendTime between our get() and CAS; re-read and retry.
            previous = lastSendTime.get();
        }

        String sanitized = sanitize(message);
        if (sanitized.isBlank()) {
            return SendResult.EMPTY;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) {
            LOGGER.warn("Cannot send chat message: not connected");
            return SendResult.DISCONNECTED;
        }

        client.execute(() -> sendOnClientThread(client, sanitized));
        return SendResult.SENT;
    }

    /**
     * Pure decision helper — extracted so it can be tested without a Minecraft instance.
     * Returns the {@link SendResult} that {@link #send} would return given these inputs,
     * but does NOT mutate any state.
     */
    static SendResult decide(long now, long prevLastSendTime, long minIntervalMs, String message) {
        if (message == null || message.isBlank()) {
            return SendResult.EMPTY;
        }
        if (now - prevLastSendTime < minIntervalMs) {
            return SendResult.RATE_LIMITED;
        }
        return SendResult.SENT;
    }

    /**
     * Strip characters that are illegal in Minecraft chat:
     * newlines, backticks, control characters, and other non-printable symbols.
     */
    private static String sanitize(String message) {
        return message
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replace('`', '\'')
                .replaceAll("[^\\x20-\\x7E]", "")
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
            client.player.connection.sendCommand(message.substring(1));
        } else {
            client.player.connection.sendChat(message);
        }
    }
}
