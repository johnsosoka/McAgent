package com.mcagent.fabric.queue;

import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.telemetry.CorrelationId;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.fabric.FabricChatSender;
import com.mcagent.fabric.FabricChatSender.SendResult;

import java.util.Comparator;
import java.util.concurrent.*;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central event coordinator for the fabric-mod layer.
 *
 * <p>Manages three logical channels:</p>
 * <ul>
 *   <li><b>Inbound queue</b> — player chat commands (priority blocking queue, dedup, backpressure).</li>
 *   <li><b>Outbound queue</b> — LLM chat responses (rate-limited drain to {@link FabricChatSender}).</li>
 *   <li><b>Framework buffer</b> — Baritone / game status messages (throttled, deduplicated, batched).</li>
 * </ul>
 *
 * <p>A single dispatcher thread pulls from the inbound queue and invokes the LLM service.
 * Tool calls that touch Baritone are dispatched back to the client thread via
 * {@link ClientThreadExecutor}.</p>
 */
public class BotEventQueue {
    private static final Logger log = LoggerFactory.getLogger(BotEventQueue.class);


    private static final int INBOUND_CAPACITY = 8;
    private static final int OUTBOUND_CAPACITY = 4;
    private static final long OUTBOUND_DRAIN_INTERVAL_MS = 500;
    private static final long DEDUP_WINDOW_MS = 500;

    private final PriorityBlockingQueue<InboundEvent> inboundQueue;
    private final BlockingQueue<String> outboundQueue;
    private final FrameworkMessageBuffer frameworkBuffer;
    private final Function<String, SendResult> chatSender;
    private final Counters counters;

    private final ExecutorService inboundDispatcher;
    private final ScheduledExecutorService outboundDrainer;
    private final ExecutorService urgentDispatcher;

    private LangChain4jService langChainService;
    private volatile boolean running;

    public BotEventQueue(FrameworkMessageBuffer frameworkBuffer, Counters counters) {
        this(frameworkBuffer, counters, FabricChatSender::send);
    }

    /**
     * Package-private constructor for testing — allows injection of a fake chat sender
     * and no-op counters without needing a Minecraft runtime.
     */
    BotEventQueue(FrameworkMessageBuffer frameworkBuffer, Counters counters, Function<String, SendResult> chatSender) {
        this.frameworkBuffer = frameworkBuffer;
        this.counters = counters;
        this.chatSender = chatSender;
        this.inboundQueue = new PriorityBlockingQueue<>(INBOUND_CAPACITY, Comparator.comparingInt(e -> e.priority().ordinal()));
        this.outboundQueue = new LinkedBlockingQueue<>(OUTBOUND_CAPACITY);
        this.inboundDispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mc-agent-inbound-dispatcher");
            t.setDaemon(true);
            return t;
        });
        this.outboundDrainer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mc-agent-outbound-drainer");
            t.setDaemon(true);
            return t;
        });
        this.urgentDispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mc-agent-urgent-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start processing queues. Must be called once after the Spring context is ready
     * and the {@link LangChain4jService} bean is available.
     */
    public void start(LangChain4jService langChainService) {
        if (running) {
            return;
        }
        this.langChainService = langChainService;
        this.running = true;
        inboundDispatcher.submit(this::dispatchLoop);
        outboundDrainer.scheduleWithFixedDelay(
                this::drainOutbound, OUTBOUND_DRAIN_INTERVAL_MS, OUTBOUND_DRAIN_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
        log.info("BotEventQueue started. Inbound capacity={}, outbound capacity={}", INBOUND_CAPACITY, OUTBOUND_CAPACITY);
    }

    /**
     * Enqueue a player command. Duplicate commands within the dedup window are dropped.
     * If the queue is full, the oldest non-priority item is dropped and the player is notified.
     */
    public void enqueueCommand(String playerName, String command, EventPriority priority) {
        if (!running) {
            log.warn("Queue not running; dropping command from {}", playerName);
            return;
        }
        String cmdId = CorrelationId.generate();
        InboundEvent event = new InboundEvent(playerName, command, priority, System.currentTimeMillis(), cmdId);

        // Simple dedup: if an identical command is already in the queue, skip it
        if (inboundQueue.stream().anyMatch(e -> e.playerName().equals(playerName)
                && e.command().equals(command)
                && System.currentTimeMillis() - e.timestamp() < DEDUP_WINDOW_MS)) {
            log.debug("Deduplicated command from {}: {}", playerName, command);
            counters.inc("inbound.deduped");
            return;
        }

        if (inboundQueue.size() >= INBOUND_CAPACITY) {
            // Evict the lowest-priority / oldest item
            InboundEvent toEvict = inboundQueue.stream()
                    .min(Comparator.<InboundEvent>comparingInt(e -> e.priority().ordinal())
                            .thenComparingLong(InboundEvent::timestamp))
                    .orElse(null);
            if (toEvict != null && toEvict.priority().ordinal() <= priority.ordinal()) {
                inboundQueue.remove(toEvict);
                log.warn("Queue full; dropped oldest command from {}: {}", toEvict.playerName(), toEvict.command());
                counters.inc("inbound.dropped");
                // Notify the player their command was dropped
                enqueueOutbound("I'm busy processing other commands, one moment please.");
            } else {
                log.warn("Queue full and new command is lower priority; dropping new command from {}: {}", playerName, command);
                counters.inc("inbound.dropped");
                return;
            }
        }

        inboundQueue.offer(event);
        counters.inc("inbound.enqueued");
        log.debug("Enqueued command from {} (priority={}, cmd_id={}): {}", playerName, priority, cmdId, command);
    }

    /**
     * Enqueue a chat message to be sent to the player. Rate-limited by the outbound drainer.
     */
    public void enqueueOutbound(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (outboundQueue.offer(message)) {
            counters.inc("outbound.enqueued");
        } else {
            log.warn("Outbound queue full; dropped message: {}", message);
            counters.inc("outbound.dropped");
        }
    }

    /**
     * Publish a framework status message. Delegates to the {@link FrameworkMessageBuffer}.
     */
    public void publishFramework(String message) {
        frameworkBuffer.publish(message);
    }

    /**
     * Trigger immediate LLM processing of a framework observation.
     * Used in active observation mode to let the bot react to threats
     * or opportunities without waiting for player input.
     */
    public void triggerUrgentFramework(String message) {
        if (!running) {
            log.warn("Queue not running; dropping urgent framework message");
            return;
        }
        urgentDispatcher.submit(() -> {
            String urgentCmdId = "urgent-" + CorrelationId.generate();
            try (CorrelationId.MdcScope ignored = CorrelationId.scope(urgentCmdId)) {
                try {
                    String response = langChainService.processUrgentObservation(message);
                    if (response != null && !response.isBlank()) {
                        enqueueOutbound(response);
                    }
                } catch (Exception e) {
                    log.error("Error processing urgent framework message", e);
                }
            }
        });
    }

    /**
     * Shut down all background threads and flush pending framework messages.
     */
    public void shutdown() {
        running = false;
        inboundDispatcher.shutdownNow();
        outboundDrainer.shutdownNow();
        urgentDispatcher.shutdownNow();
        frameworkBuffer.flush();
        frameworkBuffer.shutdown();
        log.info("BotEventQueue shut down.");
    }

    private void dispatchLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                InboundEvent event = inboundQueue.take();
                processInbound(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error dispatching inbound event", e);
            }
        }
    }

    private void processInbound(InboundEvent event) {
        counters.inc("inbound.dispatched");
        try (var scope = CorrelationId.scope(event.cmdId())) {
            log.info("Processing command from {} (priority={}, cmd_id={}): {}",
                    event.playerName(), event.priority(), event.cmdId(), event.command());
            try {
                // LLM processing + tool invocations happen on this dispatcher thread.
                // If a tool touches Baritone, FabricBaritoneBridge dispatches to the client thread.
                String response = langChainService.processInput(event.command(), event.playerName());
                if (response != null && !response.isBlank()) {
                    enqueueOutbound(response);
                }
            } catch (Exception e) {
                counters.inc("inbound.errors");
                log.error("Error processing command from {}", event.playerName(), e);
                enqueueOutbound("Sorry, something went wrong. Check the logs.");
            }
        }
    }

    /**
     * Returns the number of commands currently waiting in the inbound queue.
     *
     * // VisibleForTesting
     */
    int inboundQueueSize() {
        return inboundQueue.size();
    }

    /**
     * Drain the inbound queue without dispatching any events to the LLM.
     * Used by the hard-stop bypass so that queued commands do not execute
     * after the player cancels the current operation.
     */
    public void clearInbound() {
        int cleared = 0;
        while (inboundQueue.poll() != null) {
            cleared++;
        }
        if (cleared > 0) {
            log.info("Cleared {} inbound events on hard-stop", cleared);
        }
    }

    // Package-private so BotEventQueueTest can invoke the drain step directly.
    void drainOutbound() {
        // Peek first — only remove from the queue once we know the send succeeded.
        // If the sender is rate-limited, we leave the message at the head so the
        // next drain tick will retry it rather than silently losing it.
        String message = outboundQueue.peek();
        if (message == null) {
            return;
        }
        try {
            SendResult result = chatSender.apply(message);
            switch (result) {
                case SENT -> {
                    outboundQueue.poll();
                    counters.inc("outbound.sent");
                }
                case EMPTY -> outboundQueue.poll();
                case DISCONNECTED -> {
                    // DISCONNECTED: client offline — message cannot be delivered.
                    // Discard rather than retry indefinitely.
                    log.debug("Discarding outbound message because client is disconnected: {}", message);
                    outboundQueue.poll();
                    counters.inc("outbound.discarded_disconnected");
                }
                case RATE_LIMITED -> {
                    // Leave at head; next tick will retry.
                    counters.inc("outbound.rate_limited");
                }
            }
        } catch (Exception e) {
            log.error("Failed to send outbound chat message", e);
            // Consume the offending message so we don't spin forever on one bad entry.
            outboundQueue.poll();
        }
    }
}
