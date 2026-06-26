package com.mcagent.core.memory.episodic;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a significant event in the bot's life: player commands,
 * plan completions, plan failures, and corrections.
 *
 * <p>Events are scored at write time for importance (1–10) and embedded for
 * semantic similarity. At retrieval time, three factors combine to surface the
 * most useful memories: relevance (cosine sim), recency (exponential decay),
 * and importance. See {@link DefaultEpisodicMemoryService} for scoring details.</p>
 */
@Entity
@Table(name = "agent_event", indexes = {
        @Index(name = "idx_agent_event_session_created", columnList = "sessionId, createdAt DESC")
})
@Data
@NoArgsConstructor
public class AgentEvent {

    /** Type constant: player issued a command to the bot. */
    public static final String TYPE_COMMAND = "command";

    /** Type constant: a plan step completed successfully. */
    public static final String TYPE_PLAN_STEP_COMPLETE = "plan_step_complete";

    /** Type constant: an entire plan completed successfully. */
    public static final String TYPE_PLAN_COMPLETE = "plan_complete";

    /** Type constant: a plan or step failed. */
    public static final String TYPE_PLAN_FAIL = "plan_fail";

    /** Type constant: an autonomous observation the bot made. */
    public static final String TYPE_OBSERVATION = "observation";

    /** Type constant: an explicit player correction ("that's wrong, do X instead"). */
    public static final String TYPE_CORRECTION = "correction";

    /** Type constant: a post-task reflection summary. */
    public static final String TYPE_REFLECTION = "reflection";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Event category. One of the {@code TYPE_*} constants defined in this class.
     */
    @Column(nullable = false)
    private String type;

    /**
     * Human-readable description of what happened.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Importance score assigned by the LLM at write time, on a 1–10 scale.
     * 1 = trivial routine; 10 = critical correction or major milestone.
     * Defaults to 5 on parse error.
     */
    private int importance;

    /**
     * Wall-clock time the event occurred. Used to compute recency decay at retrieval.
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * Server address identifying the play session. Events are always retrieved
     * within the scope of a single session so memories don't bleed across servers.
     */
    @Column(nullable = false)
    private String sessionId;

    /**
     * Serialized {@code float[]} (JSON array) produced by the embedding model.
     * Stored as TEXT because column width for vectors is model-dependent.
     */
    @Column(columnDefinition = "TEXT")
    private String embeddingJson;

    /**
     * Optional UUID (as String) linking this event back to the plan or task that
     * produced it. Null for player-command events.
     */
    private String relatedTaskId;

    /**
     * Convenience constructor used in tests and the service implementation.
     */
    public AgentEvent(String type, String content, int importance, Instant createdAt,
                      String sessionId, String embeddingJson, String relatedTaskId) {
        this.type = type;
        this.content = content;
        this.importance = importance;
        this.createdAt = createdAt;
        this.sessionId = sessionId;
        this.embeddingJson = embeddingJson;
        this.relatedTaskId = relatedTaskId;
    }
}
