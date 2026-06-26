package com.mcagent.core.task;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity that persists the state of an executing (or recently completed) {@link Plan}.
 *
 * <p>Written on every state transition so the executor can resume after a disconnect.
 * On reconnect, {@link SessionStateRepository} is queried for a recently-updated
 * {@code RUNNING} entry that matches the current server address.</p>
 *
 * <p>The plan's step list is stored as JSON (via {@code planJson}) rather than a
 * normalized table so that evolving the {@link Step} schema does not require
 * additional migration columns — only the Jackson polymorphic deserializer needs
 * to understand the format.</p>
 */
@Entity
@Table(name = "session_state")
public class SessionState {

    @Id
    private UUID taskId;

    /**
     * Full {@link Plan} serialized to JSON by {@link PlanSerializer}.
     * Column type TEXT to handle arbitrarily long step lists.
     */
    @Column(columnDefinition = "TEXT")
    private String planJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskState state;

    /** Zero-based index of the step that was executing at the last persist. */
    private int currentStepIndex;

    private Instant startedAt;

    /** Automatically updated on every save via {@link #preSave()}. */
    private Instant updatedAt;

    /** Serialized {@link FailureReason} if the last step failed; otherwise null. */
    @Column(columnDefinition = "TEXT")
    private String lastFailureReason;

    private String requestedBy;

    /**
     * Logical server address (e.g. {@code "play.example.com:25565"} or
     * {@code "single-player"}).  Used to scope resume logic so a plan
     * started on server A does not accidentally resume on server B.
     */
    private String serverAddress;

    @PrePersist
    @PreUpdate
    void preSave() {
        updatedAt = Instant.now();
    }

    // Getters and setters — kept minimal; records are used outside persistence boundary.

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }

    public TaskState getState() { return state; }
    public void setState(TaskState state) { this.state = state; }

    public int getCurrentStepIndex() { return currentStepIndex; }
    public void setCurrentStepIndex(int currentStepIndex) { this.currentStepIndex = currentStepIndex; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public String getLastFailureReason() { return lastFailureReason; }
    public void setLastFailureReason(String lastFailureReason) { this.lastFailureReason = lastFailureReason; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }

    public String getServerAddress() { return serverAddress; }
    public void setServerAddress(String serverAddress) { this.serverAddress = serverAddress; }
}
