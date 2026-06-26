package com.mcagent.core.service.ops;

import java.util.function.Consumer;

/**
 * Lifecycle and Baritone-settings control operations.
 * These are the knobs that modify how the bot behaves, as opposed to
 * what it does (navigation) or what it sees (observation).
 */
public interface BotControlOperations {

    /** Cancel all active Baritone processes. */
    void cancel();

    /** Pause the current operation (no-op in Baritone API versions that lack it). */
    void pause();

    /** Resume a paused operation (no-op in Baritone API versions that lack it). */
    void resume();

    /**
     * Enable or disable safety mode. When enabled, the bot avoids mobs,
     * disables parkour/sprint, avoids breaking blocks.
     */
    void setSafetyMode(boolean enabled);

    /**
     * Set pathing behavior mode: "careful", "aggressive", or "default".
     */
    void setPathingBehavior(String mode);

    /**
     * Add a block type to the avoid-breaking list.
     * blockType should be a Minecraft block ID, e.g. "minecraft:glass".
     */
    void addBlockToAvoid(String blockType);

    /**
     * Clear all custom block avoidance rules and restore defaults.
     */
    void clearAvoidedBlocks();

    /**
     * Register a callback that receives human-readable progress/status messages
     * from the bot (e.g. "Arrived at destination", "Mining complete").
     */
    void setProgressCallback(Consumer<String> callback);

    /**
     * Initiate eating a food item from the bot's inventory.
     *
     * <p><b>Optimistic / best-effort contract:</b> food consumption is server-authoritative
     * and takes approximately 32 ticks (~1.6 s) to complete. This method returns
     * {@code true} as soon as a valid food slot has been selected and the use-item
     * packet dispatched — meaning "eat initiated successfully." A {@code false} return
     * means no matching food was found in the inventory.</p>
     *
     * <p>Callers must not rely on the food level having risen by the time this method
     * returns. If confirmation semantics are needed, schedule a follow-up check after
     * at least 40 ticks.</p>
     *
     * @param itemId specific Minecraft item ID (e.g. "minecraft:bread"), or {@code null}
     *               for auto-best selection from the priority food list
     * @return {@code true} if a food item was found and consumption initiated;
     *         {@code false} if no matching food is in inventory
     */
    boolean eat(String itemId);

    /**
     * Place a torch at the bot's feet or the nearest suitable adjacent surface.
     * Requires at least one {@code minecraft:torch} in inventory.
     * Silent on success — the reflex layer calls this without player notification.
     */
    void placeTorch();
}
