package com.mcagent.core.telemetry;

import com.mcagent.core.service.BotOperations.Location;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Tracks the currently active long-running bot job (mining, building, navigating, etc.)
 * and records death events for the current session.
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
     * @param description      Short human-readable description of the job, e.g. "Mining IRON_ORE".
     * @param startedAt        Instant the job was recorded.
     * @param playerWhoStarted Name of the player who issued the command, or "unknown".
     */
    public record Job(String description, Instant startedAt, String playerWhoStarted) {}

    /**
     * Record of a bot death within the current session.
     *
     * @param location               Coordinates where the bot died.
     * @param occurredAt             Instant the death was detected.
     * @param suspendedJobDescription Human-readable description of the job that was
     *                               active at the time of death, or {@code null} if idle.
     */
    public record Death(Location location, Instant occurredAt, String suspendedJobDescription) {}

    private volatile Job currentJob;
    private volatile String lastProgress = "";
    private volatile Death lastDeath;

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

    /**
     * Record a bot death. Captures the location and the description of whichever
     * job was active at the time (may be {@code null} when the bot was idle).
     *
     * @param location     Coordinates where the death occurred.
     * @param suspendedJob Job that was running when the bot died, or {@code null}.
     */
    public void recordDeath(Location location, Job suspendedJob) {
        String desc = suspendedJob != null ? suspendedJob.description() : null;
        this.lastDeath = new Death(location, Instant.now(), desc);
    }

    /**
     * Return the most recent death recorded this session, or {@link Optional#empty()}
     * if the bot has not died since the Spring context was created.
     */
    public Optional<Death> getLastDeath() {
        return Optional.ofNullable(lastDeath);
    }
}
