package com.mcagent.core.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link SessionState} persistence.
 *
 * <p>The {@link #findFirstByStateAndUpdatedAtAfterOrderByUpdatedAtDesc} method is used
 * on reconnect to recover a recently-running plan within the 5-minute grace window.</p>
 */
public interface SessionStateRepository extends JpaRepository<SessionState, UUID> {

    /**
     * Find the most recently updated session that has the given state and was
     * updated after the given timestamp.  Used by the resume-on-reconnect path
     * to find a {@link TaskState#RUNNING} plan from before a brief disconnect.
     *
     * @param state the task state to match (typically {@link TaskState#RUNNING})
     * @param after only consider sessions updated after this instant
     * @return the most recently updated matching session, or empty if none found
     */
    Optional<SessionState> findFirstByStateAndUpdatedAtAfterOrderByUpdatedAtDesc(
            TaskState state, Instant after);
}
