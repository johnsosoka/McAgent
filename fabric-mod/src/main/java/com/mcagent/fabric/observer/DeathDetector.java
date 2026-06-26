package com.mcagent.fabric.observer;

import com.mcagent.core.service.BotOperations.Location;

/**
 * Pure state-machine for death/respawn edge detection.
 *
 * <p>This class contains no Minecraft dependencies so it can be unit-tested
 * on a plain JVM. The Minecraft-aware call sites in {@link AutonomousObserver}
 * read {@code player.isDeadOrDying()} and extract coordinates, then delegate
 * the transition logic here.</p>
 *
 * <p>Thread safety: instances are created per-{@link AutonomousObserver} and
 * accessed exclusively from the Minecraft client thread via the tick callback.</p>
 */
public class DeathDetector {

    /** Raised when the bot transitions from alive to dead. */
    public record DeathEvent(Location location) {}

    /** Raised when the bot transitions from dead to alive (respawn). */
    public record RespawnEvent(Location location) {}

    private boolean wasAlive = true;

    /**
     * Process the current alive/dead state and return a transition event if one occurred.
     *
     * @param isDead    whether the player is currently dead or dying
     * @param location  current player location (used to populate the event)
     * @return a {@link DeathEvent}, a {@link RespawnEvent}, or {@code null} if no transition
     */
    public Object tick(boolean isDead, Location location) {
        if (wasAlive && isDead) {
            wasAlive = false;
            return new DeathEvent(location);
        }
        if (!wasAlive && !isDead) {
            wasAlive = true;
            return new RespawnEvent(location);
        }
        return null;
    }

    /** Return {@code true} if the detector currently considers the player alive. */
    public boolean isConsideredAlive() {
        return wasAlive;
    }
}
