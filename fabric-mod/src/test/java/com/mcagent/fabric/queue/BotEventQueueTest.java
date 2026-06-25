package com.mcagent.fabric.queue;

import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.fabric.FabricChatSender.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link BotEventQueue}'s outbound drain logic and counter instrumentation.
 *
 * <p>Tests use the package-private constructor to inject a fake chat sender and
 * a real {@link Counters} instance, avoiding any Minecraft runtime dependency.</p>
 */
class BotEventQueueTest {

    private static final int CAPACITY = 5;
    private static final long THROTTLE_MS = 60_000L; // large — prevents auto-delivery in tests

    private BotEventQueue queue;
    private Counters counters;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    private FrameworkMessageBuffer testBuffer() {
        counters = new Counters();
        return new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters);
    }

    /**
     * When the sender returns RATE_LIMITED, the message must stay at the head of the
     * outbound queue so the next drain tick can retry. The message must not be lost.
     */
    @Test
    void outboundQueue_rateLimitedMessageRequeues() {
        AtomicInteger callCount = new AtomicInteger(0);
        List<String> sent = new ArrayList<>();

        // First call → RATE_LIMITED; subsequent calls → SENT
        counters = new Counters();
        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> {
                    int n = callCount.incrementAndGet();
                    if (n == 1) {
                        return SendResult.RATE_LIMITED;
                    }
                    sent.add(msg);
                    return SendResult.SENT;
                }
        );

        queue.enqueueOutbound("hello");

        // First drain — RATE_LIMITED; message stays at head
        queue.drainOutbound();
        assertThat(sent).isEmpty();

        // Second drain — SENT; message consumed
        queue.drainOutbound();
        assertThat(sent).containsExactly("hello");

        // Third drain — queue is empty; nothing happens
        queue.drainOutbound();
        assertThat(sent).hasSize(1);
    }

    /**
     * A SENT result removes the message from the head of the queue.
     */
    @Test
    void outboundQueue_sentResultConsumesMessage() {
        List<String> sent = new ArrayList<>();
        counters = new Counters();
        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> {
                    sent.add(msg);
                    return SendResult.SENT;
                }
        );

        queue.enqueueOutbound("first");
        queue.enqueueOutbound("second");

        queue.drainOutbound();
        queue.drainOutbound();

        assertThat(sent).containsExactly("first", "second");
    }

    /**
     * An EMPTY result (null/blank message) is also consumed without loss of the
     * next real message.
     */
    @Test
    void outboundQueue_emptyResultConsumesSlot() {
        List<String> sent = new ArrayList<>();
        AtomicInteger drainCount = new AtomicInteger(0);
        counters = new Counters();
        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> {
                    int n = drainCount.incrementAndGet();
                    if (n == 1) {
                        return SendResult.EMPTY;
                    }
                    sent.add(msg);
                    return SendResult.SENT;
                }
        );

        queue.enqueueOutbound("real");

        queue.drainOutbound(); // returns EMPTY — slot is consumed
        queue.drainOutbound(); // queue was consumed on first call, nothing left
        assertThat(sent).isEmpty(); // no second message was in queue

        // Enqueue and drain again to confirm queue is operational
        queue.enqueueOutbound("second");
        queue.drainOutbound();
        assertThat(sent).containsExactly("second");
    }

    /**
     * clearInbound drains all pending inbound commands and never dispatches any of them
     * to the LLM service.
     *
     * <p>A blocking LangChain4jService mock ensures the dispatcher thread cannot consume
     * events between enqueue and the clearInbound call under test.</p>
     */
    @Test
    void clearInbound_removesAllPendingCommands_withoutDispatchingToLlm() throws InterruptedException {
        LangChain4jService mockLlm = mock(LangChain4jService.class);
        // Block the dispatcher thread so it cannot dequeue items before clearInbound runs.
        CountDownLatch dispatcherBlocked = new CountDownLatch(1);
        CountDownLatch releaseDispatcher = new CountDownLatch(1);
        org.mockito.Mockito.when(mockLlm.processInput(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    dispatcherBlocked.countDown();
                    releaseDispatcher.await();
                    return "blocked";
                });

        counters = new Counters();
        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> SendResult.SENT
        );
        queue.start(mockLlm);

        // Fill the inbound queue with two commands; the first one will unblock
        // the mock LLM and the dispatcher will be held there while we clear.
        queue.enqueueCommand("alice", "mine iron", EventPriority.NORMAL);
        queue.enqueueCommand("alice", "navigate home", EventPriority.NORMAL);

        // Wait until the dispatcher picks up the first command and blocks inside the mock.
        dispatcherBlocked.await(2, java.util.concurrent.TimeUnit.SECONDS);

        // At this point the second command is still in the inbound queue.
        assertThat(queue.inboundQueueSize()).isGreaterThan(0);

        // Clear should remove the remaining queued command immediately.
        queue.clearInbound();

        assertThat(queue.inboundQueueSize()).isEqualTo(0);

        // Release the dispatcher and verify the mock was called only for the
        // first command (which was already being processed, not from clearInbound).
        releaseDispatcher.countDown();
        verify(mockLlm, never()).processInput("navigate home", "alice");
    }

    /**
     * Outbound counter increments are verified independently of the inbound dispatcher
     * (which requires running=true and an LLM service). We test the outbound path
     * directly since {@code enqueueOutbound} and {@code drainOutbound} are both
     * callable without starting the dispatcher.
     */
    @Test
    void counters_trackOutboundEnqueueAndSent() {
        List<String> sent = new ArrayList<>();
        counters = new Counters();
        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> {
                    sent.add(msg);
                    return SendResult.SENT;
                }
        );

        queue.enqueueOutbound("Mining started.");
        assertThat(counters.get("outbound.enqueued")).isEqualTo(1L);

        queue.drainOutbound();
        assertThat(counters.get("outbound.sent")).isEqualTo(1L);
        assertThat(sent).containsExactly("Mining started.");
    }

    /**
     * Verify inbound.enqueued counter increments by starting the dispatcher with a
     * blocking mock that holds the first dequeued command in-flight so we can observe
     * the counter snapshot before commands are dispatched.
     */
    @Test
    void counters_trackInboundEnqueued() throws InterruptedException {
        counters = new Counters();
        CountDownLatch dispatcherBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        LangChain4jService blockingLlm = mock(LangChain4jService.class);
        org.mockito.Mockito.when(blockingLlm.processInput(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    dispatcherBlocked.countDown();
                    release.await();
                    return null;
                });

        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> SendResult.SENT
        );
        queue.start(blockingLlm);

        queue.enqueueCommand("alice", "mine iron", EventPriority.NORMAL);
        queue.enqueueCommand("bob", "navigate home", EventPriority.NORMAL);

        // Wait for dispatcher to pick up the first item and block.
        dispatcherBlocked.await(2, java.util.concurrent.TimeUnit.SECONDS);

        // Both were enqueued before the dispatcher removed the first.
        assertThat(counters.get("inbound.enqueued")).isEqualTo(2L);

        release.countDown();
    }

    /**
     * RATE_LIMITED drains leave the message at head and increment the rate_limited counter.
     * The outbound queue does not require running=true, so no start() needed here.
     */
    @Test
    void counters_trackRateLimited() {
        counters = new Counters();
        AtomicInteger call = new AtomicInteger(0);
        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> call.incrementAndGet() == 1 ? SendResult.RATE_LIMITED : SendResult.SENT
        );

        queue.enqueueOutbound("hello");
        assertThat(counters.get("outbound.enqueued")).isEqualTo(1L);

        queue.drainOutbound(); // RATE_LIMITED
        assertThat(counters.get("outbound.rate_limited")).isEqualTo(1L);
        assertThat(counters.get("outbound.sent")).isEqualTo(0L);

        queue.drainOutbound(); // SENT
        assertThat(counters.get("outbound.sent")).isEqualTo(1L);
    }
}
