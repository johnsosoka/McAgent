package com.mcagent.fabric.queue;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Utility that dispatches Baritone and Minecraft client calls back onto the
 * main client thread. This fixes the thread-safety gap where Spring Boot's
 * chat executor (or the LLM tool invocation thread) calls BotOperations
 * methods off-thread.
 *
 * <p>If already on the client thread, the action runs immediately. Otherwise it
 * is scheduled via {@link Minecraft#execute(Runnable)} and the calling thread
 * blocks until the result is available (with a timeout).</p>
 */
public final class ClientThreadExecutor {

    private static final long DEFAULT_TIMEOUT_SECONDS = 5;

    private ClientThreadExecutor() {
        // utility class
    }

    /**
     * Execute a supplier on the main client thread and return its result.
     *
     * @param action the work to perform on the client thread
     * @param <T>    result type
     * @return the result produced on the client thread
     * @throws IllegalStateException if the client thread does not complete within the timeout
     */
    public static <T> T execute(Supplier<T> action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            return action.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Client thread execution failed or timed out", e);
        }
    }

    /**
     * Execute a runnable on the main client thread and block until complete.
     *
     * @param action the work to perform on the client thread
     * @throws IllegalStateException if the client thread does not complete within the timeout
     */
    public static void execute(Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            action.run();
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Client thread execution failed or timed out", e);
        }
    }
}
