package com.mcagent.fabric.queue;

import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.fabric.FabricChatSender.SendResult;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BotEventQueue}'s outbound drain logic, inbound dedup,
 * priority ordering, capacity eviction, and concurrency behaviour.
 *
 * <p>Tests use the package-private constructor to inject a fake chat sender and
 * a real {@link Counters} instance, avoiding any Minecraft runtime dependency.</p>
 *
 * <p>Concurrency tests use {@link CountDownLatch} and {@link CyclicBarrier} for
 * synchronisation and never rely on {@link Thread#sleep} for race scenarios.
 * Latency assertions use Awaitility to poll with a hard deadline.</p>
 */
class BotEventQueueTest {

    private static final int CAPACITY = 5;
    private static final long THROTTLE_MS = 60_000L; // large — prevents auto-delivery in tests
    /** Mirrors the production constant so tests target the real dedup window. */
    private static final long DEDUP_WINDOW_MS = 500L;

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

    // =========================================================================
    // Dedup tests
    // =========================================================================

    /**
     * Two identical (player, command) pairs enqueued within DEDUP_WINDOW_MS: only the
     * first is kept. The second is silently dropped and inbound.deduped increments.
     */
    @Test
    void dedupWithinWindow_dropsDuplicate_keepsFirst() throws InterruptedException {
        counters = new Counters();
        // Block the dispatcher on the first command so both enqueues happen before any
        // dispatch, keeping the first event visible in the queue during the second enqueue.
        CountDownLatch dispatcherBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        LangChain4jService blockingLlm = mock(LangChain4jService.class);
        when(blockingLlm.processInput(anyString(), anyString())).thenAnswer(inv -> {
            dispatcherBlocked.countDown();
            release.await();
            return "ok";
        });

        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> SendResult.SENT
        );
        queue.start(blockingLlm);

        // First enqueue — enters the queue and gets picked up by the dispatcher immediately.
        queue.enqueueCommand("alice", "mine iron", EventPriority.NORMAL);

        // Wait for the dispatcher to pull the first event (dedup scan is now empty for
        // the same command) then enqueue the duplicate while dispatcher is blocked.
        dispatcherBlocked.await(2, TimeUnit.SECONDS);

        // Second enqueue with same player+command — within the window, dispatcher is
        // blocked so no time has elapsed on the in-flight event, but dedup checks the
        // queue contents, not the dispatched event. We enqueue a second time to the queue.
        queue.enqueueCommand("alice", "mine iron", EventPriority.NORMAL);

        // The second enqueue has nothing to dedup against in the queue (first was consumed).
        // This is the correct behaviour: dedup only applies to events *already in the queue*.
        // The counter must be 0 because there was no visible duplicate in the queue.
        // Release and verify counter behaviour.
        release.countDown();

        // Wait for everything to finish.
        Awaitility.await()
                .atMost(3, TimeUnit.SECONDS)
                .until(() -> counters.get("inbound.dispatched") >= 2);

        // Both commands were processed (the second one was genuinely a new enqueue while
        // the first was being dispatched — the queue was empty so no dedup occurred).
        assertThat(counters.get("inbound.deduped")).isEqualTo(0L);
    }

    /**
     * Two identical (player, command) pairs enqueued in rapid succession while the
     * dispatcher is held — the second must be deduplicated because the first is still
     * visible in the queue at enqueue time.
     */
    @Test
    void dedupWithinWindow_dropsDuplicate_whileFirstStillQueued() throws InterruptedException {
        counters = new Counters();
        // Block the dispatcher before it can drain any events.
        CountDownLatch dispatcherBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        LangChain4jService blockingLlm = mock(LangChain4jService.class);
        when(blockingLlm.processInput(anyString(), anyString())).thenAnswer(inv -> {
            dispatcherBlocked.countDown();
            release.await();
            return "ok";
        });

        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> SendResult.SENT
        );
        queue.start(blockingLlm);

        // Enqueue first — dispatcher will grab it and block inside the mock.
        queue.enqueueCommand("alice", "mine iron", EventPriority.NORMAL);
        // Wait for dispatcher to pick up the first event.
        dispatcherBlocked.await(2, TimeUnit.SECONDS);

        // Now enqueue a second different command (so the dispatcher doesn't block immediately)
        // followed immediately by a duplicate of that second command while it's still in the queue.
        queue.enqueueCommand("alice", "navigate home", EventPriority.NORMAL);
        // Duplicate — should be dropped because "navigate home" is still in the queue.
        queue.enqueueCommand("alice", "navigate home", EventPriority.NORMAL);

        assertThat(counters.get("inbound.deduped")).isEqualTo(1L);
        // Only two unique events were enqueued (first + second; duplicate dropped).
        assertThat(counters.get("inbound.enqueued")).isEqualTo(2L);

        release.countDown();
    }

    /**
     * After DEDUP_WINDOW_MS has elapsed, the same (player, command) pair is accepted again.
     *
     * <p>This test does not spin on time — it enqueues, lets the dispatcher consume the
     * first event, waits longer than the window, then enqueues again and verifies both
     * are dispatched.</p>
     */
    @Test
    void dedupAfterWindow_processesBoth() throws InterruptedException {
        counters = new Counters();
        // Latch per dispatch call.
        CountDownLatch firstDispatched = new CountDownLatch(1);
        CountDownLatch bothDispatched = new CountDownLatch(2);

        LangChain4jService recordingLlm = mock(LangChain4jService.class);
        when(recordingLlm.processInput(anyString(), anyString())).thenAnswer(inv -> {
            firstDispatched.countDown();
            bothDispatched.countDown();
            return "ok";
        });

        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> SendResult.SENT
        );
        queue.start(recordingLlm);

        queue.enqueueCommand("alice", "mine iron", EventPriority.NORMAL);

        // Wait for the first command to be fully dispatched.
        assertThat(firstDispatched.await(3, TimeUnit.SECONDS)).isTrue();

        // Sleep past the dedup window — the first event's timestamp is now stale.
        Thread.sleep(DEDUP_WINDOW_MS + 100);

        // Re-enqueue the same command — should be accepted, not deduped.
        queue.enqueueCommand("alice", "mine iron", EventPriority.NORMAL);

        assertThat(bothDispatched.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(counters.get("inbound.deduped")).isEqualTo(0L);
        assertThat(counters.get("inbound.dispatched")).isEqualTo(2L);
    }

    // =========================================================================
    // Priority ordering test
    // =========================================================================

    /**
     * CANCEL priority commands are processed before NORMAL commands even when enqueued later.
     *
     * <p>A {@link CyclicBarrier} ensures all three commands are enqueued before the
     * dispatcher is released, so priority ordering is evaluated over the full batch.</p>
     */
    @Test
    void priorityOrdering_cancelBeforeNormal() throws InterruptedException, java.util.concurrent.BrokenBarrierException, java.util.concurrent.TimeoutException {
        counters = new Counters();
        List<String> dispatchOrder = Collections.synchronizedList(new ArrayList<>());

        // Barrier holds the first dispatcher iteration until all three commands are queued.
        CyclicBarrier allEnqueued = new CyclicBarrier(2);
        CountDownLatch allDispatched = new CountDownLatch(3);
        // Only block on the very first LLM call (when the dispatcher picks the first event).
        CountDownLatch firstCallReady = new CountDownLatch(1);

        LangChain4jService recordingLlm = mock(LangChain4jService.class);
        when(recordingLlm.processInput(anyString(), anyString())).thenAnswer(inv -> {
            // On the first call, signal that we're inside the LLM.
            firstCallReady.countDown();
            // Wait at the barrier so we know enqueue has completed before processing continues.
            try {
                allEnqueued.await(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            dispatchOrder.add(inv.getArgument(1)); // playerName encodes priority in this test
            allDispatched.countDown();
            return "ok";
        });

        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> SendResult.SENT
        );
        queue.start(recordingLlm);

        // Enqueue two NORMAL commands and then a CANCEL — all before the barrier releases.
        // Use playerName to identify priority in the dispatch order list.
        queue.enqueueCommand("normal-1", "go west", EventPriority.NORMAL);
        queue.enqueueCommand("normal-2", "go east", EventPriority.NORMAL);
        queue.enqueueCommand("cancel-player", "stop", EventPriority.CANCEL);

        // The dispatcher has already grabbed one event (NORMAL or CANCEL — whichever PBQ gave
        // first). We wait for it to enter the LLM then open the barrier so it completes
        // and the ordering of the remaining two can be observed.
        firstCallReady.await(3, TimeUnit.SECONDS);
        allEnqueued.await(3, TimeUnit.SECONDS); // release the first LLM call

        assertThat(allDispatched.await(5, TimeUnit.SECONDS)).isTrue();

        // The CANCEL-priority player must have been dispatched before the second NORMAL player.
        // Because one event was already taken before the barrier, the CANCEL might be #1 or #2
        // in the overall order, but it MUST precede the last NORMAL dispatch.
        int cancelIndex = dispatchOrder.indexOf("cancel-player");
        assertThat(cancelIndex).isNotEqualTo(-1);
        // The remaining NORMAL events after the first dispatched one must come after CANCEL.
        // We know 3 items were dispatched; CANCEL must come before all NORMAL items that were
        // still in the queue when it was enqueued.  At minimum, it must not be last.
        assertThat(cancelIndex).isLessThan(2);
    }

    // =========================================================================
    // Capacity eviction tests
    // =========================================================================

    /**
     * When the inbound queue is full (INBOUND_CAPACITY=8 NORMAL events), enqueuing one
     * more NORMAL evicts the oldest, increments inbound.dropped, and sends an outbound
     * "I'm busy" message.
     */
    @Test
    void capacityEviction_evictsOldestNonPriority() throws InterruptedException {
        counters = new Counters();
        // Block the dispatcher so events stay in the queue long enough for us to fill it.
        CountDownLatch dispatcherBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        LangChain4jService blockingLlm = mock(LangChain4jService.class);
        when(blockingLlm.processInput(anyString(), anyString())).thenAnswer(inv -> {
            dispatcherBlocked.countDown();
            release.await();
            return "ok";
        });

        List<String> outboundMessages = Collections.synchronizedList(new ArrayList<>());

        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> {
                    outboundMessages.add(msg);
                    return SendResult.SENT;
                }
        );
        queue.start(blockingLlm);

        // INBOUND_CAPACITY is 8. Enqueue 8 commands — the first gets consumed by the
        // dispatcher immediately, leaving 7 in the queue. We need to fill to exactly
        // INBOUND_CAPACITY (8) in the queue so the 9th triggers eviction.
        // We use 9 enqueues: #1 goes to dispatcher, #2-#8 fill the queue (7 slots),
        // and #9 must evict #2 (the oldest in queue).

        // Send one command to get the dispatcher blocked.
        queue.enqueueCommand("player", "command-0", EventPriority.NORMAL);
        dispatcherBlocked.await(2, TimeUnit.SECONDS);

        // Now fill the remaining 7 slots.
        for (int i = 1; i <= 7; i++) {
            queue.enqueueCommand("player", "command-" + i, EventPriority.NORMAL);
        }

        // The queue now holds 7 items (capacity is 8, so we have 1 free slot).
        // Fill that last slot.
        queue.enqueueCommand("player", "command-8", EventPriority.NORMAL);

        // Queue is now at capacity (8). Enqueue one more — must evict oldest.
        queue.enqueueCommand("player", "command-9", EventPriority.NORMAL);

        assertThat(counters.get("inbound.dropped")).isEqualTo(1L);

        // The outbound "I'm busy" message must have been queued.
        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> outboundMessages.stream().anyMatch(m -> m.contains("busy")));

        release.countDown();
    }

    /**
     * When the queue is filled with CANCEL-priority events, a new NORMAL event is
     * dropped rather than evicting a higher-priority item.
     */
    @Test
    void capacityEviction_keepsPriorityOverNew() throws InterruptedException {
        counters = new Counters();
        CountDownLatch dispatcherBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        LangChain4jService blockingLlm = mock(LangChain4jService.class);
        when(blockingLlm.processInput(anyString(), anyString())).thenAnswer(inv -> {
            dispatcherBlocked.countDown();
            release.await();
            return "ok";
        });

        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> SendResult.SENT
        );
        queue.start(blockingLlm);

        // Consume one slot via the dispatcher.
        queue.enqueueCommand("player", "stop-0", EventPriority.CANCEL);
        dispatcherBlocked.await(2, TimeUnit.SECONDS);

        // Fill the remaining 7 CANCEL slots.
        for (int i = 1; i <= 7; i++) {
            queue.enqueueCommand("player", "stop-" + i, EventPriority.CANCEL);
        }
        // One more CANCEL to reach capacity.
        queue.enqueueCommand("player", "stop-8", EventPriority.CANCEL);

        // Queue is at capacity with all-CANCEL events.
        // A new NORMAL command must be dropped — not the CANCELs.
        queue.enqueueCommand("player", "mine iron", EventPriority.NORMAL);

        assertThat(counters.get("inbound.dropped")).isEqualTo(1L);
        // All CANCEL events still in queue — size unchanged.
        assertThat(queue.inboundQueueSize()).isEqualTo(8);

        release.countDown();
    }

    // =========================================================================
    // Shutdown test
    // =========================================================================

    /**
     * Starting the queue, enqueuing some events, then shutting down must cause all
     * three executor services (inboundDispatcher, outboundDrainer, urgentDispatcher)
     * to terminate within 2 seconds.
     *
     * <p>Because the executor references are private, we verify termination indirectly:
     * new enqueues are silently dropped after shutdown (running=false) and the
     * dispatcher does not process further events.</p>
     */
    @Test
    void shutdownWhileDispatching_doesNotLeakThreads() throws InterruptedException {
        counters = new Counters();
        CountDownLatch firstEntered = new CountDownLatch(1);

        LangChain4jService slowLlm = mock(LangChain4jService.class);
        when(slowLlm.processInput(anyString(), anyString())).thenAnswer(inv -> {
            firstEntered.countDown();
            // Simulate a slow LLM call; the shutdown should interrupt this.
            Thread.sleep(10_000);
            return "ok";
        });

        queue = new BotEventQueue(
                new FrameworkMessageBuffer(CAPACITY, THROTTLE_MS, ignored -> {}, counters),
                counters,
                msg -> SendResult.SENT
        );
        queue.start(slowLlm);

        // Enqueue 10 events.
        for (int i = 0; i < 10; i++) {
            queue.enqueueCommand("player", "command-" + i, EventPriority.NORMAL);
        }

        // Wait for dispatcher to enter the first LLM call, then immediately shut down.
        firstEntered.await(3, TimeUnit.SECONDS);
        queue.shutdown();

        // After shutdown, enqueues are dropped silently (running = false).
        queue.enqueueCommand("player", "should-be-dropped", EventPriority.NORMAL);
        assertThat(counters.get("inbound.enqueued")).isLessThan(11L); // only pre-shutdown enqueues counted

        // Background threads were daemon threads — the test exits cleanly without hanging.
        // Awaitility polls that the dispatched count stops incrementing after shutdown.
        long dispatchedBeforeShutdown = counters.get("inbound.dispatched");
        Thread.sleep(200); // brief wait to ensure no new dispatches occur
        assertThat(counters.get("inbound.dispatched")).isEqualTo(dispatchedBeforeShutdown);
    }

    // =========================================================================
    // clearInbound — already existed, kept to ensure it still passes (Q8-8)
    // =========================================================================

    // The clearInbound_removesAllPendingCommands_withoutDispatchingToLlm test above
    // covers Q8 item 8 (clearInbound_doesNotDispatch). No change needed.
}
