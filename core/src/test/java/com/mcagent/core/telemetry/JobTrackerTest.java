package com.mcagent.core.telemetry;

import com.mcagent.core.service.BotOperations.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JobTracker}.
 */
class JobTrackerTest {

    private JobTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new JobTracker();
    }

    @Test
    void initiallyIdle() {
        assertThat(tracker.getCurrentJob()).isEmpty();
        assertThat(tracker.getLastProgress()).isBlank();
        assertThat(tracker.elapsed()).isEqualTo(Duration.ZERO);
    }

    @Test
    void setCurrentJob_storesDescriptionAndPlayer() {
        tracker.setCurrentJob("Mining IRON_ORE", "Alice");

        assertThat(tracker.getCurrentJob()).isPresent();
        JobTracker.Job job = tracker.getCurrentJob().get();
        assertThat(job.description()).isEqualTo("Mining IRON_ORE");
        assertThat(job.playerWhoStarted()).isEqualTo("Alice");
        assertThat(job.startedAt()).isNotNull();
    }

    @Test
    void setCurrentJob_clearsProgressFromPreviousJob() {
        tracker.setCurrentJob("Job 1", "Alice");
        tracker.updateProgress("50/100");

        tracker.setCurrentJob("Job 2", "Alice");

        assertThat(tracker.getLastProgress()).isBlank();
    }

    @Test
    void updateProgress_storesProgress() {
        tracker.setCurrentJob("Mining", "Bob");
        tracker.updateProgress("83/500");

        assertThat(tracker.getLastProgress()).isEqualTo("83/500");
    }

    @Test
    void clearJob_makesTrackerIdle() {
        tracker.setCurrentJob("Mining", "Bob");
        tracker.updateProgress("100/500");

        tracker.clearJob();

        assertThat(tracker.getCurrentJob()).isEmpty();
        assertThat(tracker.getLastProgress()).isBlank();
        assertThat(tracker.elapsed()).isEqualTo(Duration.ZERO);
    }

    @Test
    void elapsed_isPositiveWhileJobIsRunning() throws InterruptedException {
        tracker.setCurrentJob("Exploring", "Carol");

        Thread.sleep(50);

        assertThat(tracker.elapsed()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void elapsedFormatted_showsSecondsForShortJobs() throws InterruptedException {
        tracker.setCurrentJob("test", "player");
        Thread.sleep(10); // short, well under 60s
        // Should produce something like "0s" or "1s" — either is acceptable
        String formatted = tracker.elapsedFormatted();
        assertThat(formatted).endsWith("s");
        assertThat(formatted).doesNotContain("m"); // no minutes yet
    }

    @Test
    void elapsedFormatted_returnsZeroSecondsWhenIdle() {
        // No job set — elapsed is ZERO
        assertThat(tracker.elapsedFormatted()).isEqualTo("0s");
    }

    @Test
    void updateProgress_nullTreatedAsBlank() {
        tracker.setCurrentJob("task", "player");
        tracker.updateProgress(null);

        assertThat(tracker.getLastProgress()).isBlank();
    }

    @Test
    void getLastDeath_returnsEmptyByDefault() {
        assertThat(tracker.getLastDeath()).isEmpty();
    }

    @Test
    void recordDeath_setsLastDeath() {
        Location location = new Location(10, 64, -20);
        Instant before = Instant.now();

        tracker.recordDeath(location, null);

        assertThat(tracker.getLastDeath()).isPresent();
        JobTracker.Death death = tracker.getLastDeath().get();
        assertThat(death.location()).isEqualTo(location);
        assertThat(death.occurredAt()).isAfterOrEqualTo(before);
        assertThat(death.suspendedJobDescription()).isNull();
    }

    @Test
    void recordDeath_capturesSuspendedJobDescription() {
        tracker.setCurrentJob("Mining IRON_ORE", "Alice");
        JobTracker.Job activeJob = tracker.getCurrentJob().get();
        Location location = new Location(5, 32, 5);

        tracker.recordDeath(location, activeJob);

        JobTracker.Death death = tracker.getLastDeath().get();
        assertThat(death.suspendedJobDescription()).isEqualTo("Mining IRON_ORE");
    }

    @Test
    void recordDeath_overwritesPreviousDeath() {
        Location first = new Location(0, 64, 0);
        Location second = new Location(100, 64, 100);

        tracker.recordDeath(first, null);
        tracker.recordDeath(second, null);

        assertThat(tracker.getLastDeath().get().location()).isEqualTo(second);
    }
}
