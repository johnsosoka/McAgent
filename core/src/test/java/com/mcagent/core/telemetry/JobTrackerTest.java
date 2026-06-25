package com.mcagent.core.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
