package com.mcagent.fabric.queue;

import com.mcagent.core.service.LangChain4jService;
import com.mcagent.fabric.FabricChatSender;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.concurrent.*;

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
@Slf4j
public class BotEventQueue {

    private static final int INBOUND_CAPACITY = 8;
    private static final int OUTBOUND_CAPACITY = 4;
    private static final long OUTBOUND_DRAIN_INTERVAL_MS = 500;
    private static final long DEDUP_WINDOW_MS = 500;

    private final PriorityBlockingQueue<InboundEvent> inboundQueue;
    private final BlockingQueue<String> outboundQueue;
    private final FrameworkMessageBuffer frameworkBuffer;

    private final ExecutorService inboundDispatcher;
    private final ScheduledExecutorService outboundDrainer;

    private LangChain4jService langChainService;
    private volatile boolean running;

    public BotEventQueue(FrameworkMessageBuffer frameworkBuffer) {
        this.frameworkBuffer = frameworkBuffer;
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
        InboundEvent event = new InboundEvent(playerName, command, priority, System.currentTimeMillis());

        // Simple dedup: if an identical command is already in the queue, skip it
        if (inboundQueue.stream().anyMatch(e -> e.playerName().equals(playerName)
                && e.command().equals(command)
                && System.currentTimeMillis() - e.timestamp() < DEDUP_WINDOW_MS)) {
            log.debug("Deduplicated command from {}: {}", playerName, command);
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
                // Notify the player their command was dropped
                enqueueOutbound("I'm busy processing other commands, one moment please.");
            } else {
                log.warn("Queue full and new command is lower priority; dropping new command from {}: {}", playerName, command);
                return;
            }
        }

        inboundQueue.offer(event);
        log.debug("Enqueued command from {} (priority={}): {}", playerName, priority, command);
    }

    /**
     * Enqueue a chat message to be sent to the player. Rate-limited by the outbound drainer.
     */
    public void enqueueOutbound(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (!outboundQueue.offer(message)) {
            log.warn("Outbound queue full; dropped message: {}", message);
        }
    }

    /**
     * Publish a framework status message. Delegates to the {@link FrameworkMessageBuffer}.
     */
    public void publishFramework(String message) {
        frameworkBuffer.publish(message);
    }

    /**
     * Shut down all background threads and flush pending framework messages.
     */
    public void shutdown() {
        running = false;
        inboundDispatcher.shutdownNow();
        outboundDrainer.shutdownNow();
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
        log.info("Processing command from {} (priority={}): {}", event.playerName(), event.priority(), event.command());
        try {
            // LLM processing + tool invocations happen on this dispatcher thread.
            // If a tool touches Baritone, FabricBaritoneBridge dispatches to the client thread.
            String response = langChainService.processInput(event.command(), event.playerName());
            if (response != null && !response.isBlank()) {
                enqueueOutbound(response);
            }
        } catch (Exception e) {
            log.error("Error processing command from {}", event.playerName(), e);
            enqueueOutbound("Sorry, something went wrong. Check the logs.");
        }
    }

    private void drainOutbound() {
        String message = outboundQueue.poll();
        if (message == null) {
            return;
        }
        try {
            FabricChatSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send outbound chat message", e);
        }
    }
}
