package com.mcagent.core.telemetry;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Tracks the currently active long-running bot job (mining, building, navigating, etc.).
 *
 * <p>Tools call {@link #setCurrentJob} when they begin work. The hard-stop handler
 * calls {@link #clearJob} on {@code agent stop}. The status bypass reads
 * {@link #getCurrentJob()} to compose an instant response without LLM round-trip.</p>
 *
 * <p>All fields use {@code volatile} for visibility across the inbound dispatcher,
 * urgent dispatcher, and Minecraft client threads.</p>
 */
@Component
public class JobTracker {

    /**
     * Snapshot of a currently running job.
     *
     * @param description     Short human-readable description of the job, e.g. "Mining IRON_ORE".
     * @param startedAt       Instant the job was recorded.
     * @param playerWhoStarted Name of the player who issued the command, or "unknown".
     */
    public record Job(String description, Instant startedAt, String playerWhoStarted) {}

    private volatile Job currentJob;
    private volatile String lastProgress = "";

    /**
     * Record a new active job, replacing any previous one.
     *
     * @param description Short description of the task being started.
     * @param player      Name of the player who triggered the job.
     */
    public void setCurrentJob(String description, String player) {
        this.currentJob = new Job(description, Instant.now(), player);
        this.lastProgress = "";
    }

    /**
     * Update the free-form progress string for the current job.
     * Callers should use a compact format such as {@code "83/500 blocks"}.
     */
    public void updateProgress(String progress) {
        this.lastProgress = progress != null ? progress : "";
    }

    /**
     * Clear the current job, indicating the bot is now idle.
     */
    public void clearJob() {
        this.currentJob = null;
        this.lastProgress = "";
    }

    /**
     * Return the current job, or {@link Optional#empty()} if the bot is idle.
     */
    public Optional<Job> getCurrentJob() {
        return Optional.ofNullable(currentJob);
    }

    /**
     * Return the last progress string recorded via {@link #updateProgress}, or an empty string.
     */
    public String getLastProgress() {
        return lastProgress;
    }

    /**
     * Return the elapsed time since the current job started.
     * Returns {@link Duration#ZERO} when no job is active.
     */
    public Duration elapsed() {
        Job job = currentJob;
        if (job == null) {
            return Duration.ZERO;
        }
        return Duration.between(job.startedAt(), Instant.now());
    }

    /**
     * Format elapsed time as a compact string, e.g. {@code "4m12s"} or {@code "47s"}.
     */
    public String elapsedFormatted() {
        Duration d = elapsed();
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        return (totalSeconds / 60) + "m" + (totalSeconds % 60) + "s";
    }
}
