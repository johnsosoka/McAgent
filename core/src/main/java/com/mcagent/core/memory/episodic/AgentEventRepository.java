package com.mcagent.core.memory.episodic;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link AgentEvent} entities.
 *
 * <p>All queries are scoped to a {@code sessionId} so that memories from one
 * server never leak into another session.</p>
 */
@Repository
public interface AgentEventRepository extends JpaRepository<AgentEvent, UUID> {

    /**
     * Fetch the most recent {@code N} events for the given session, newest first.
     * The {@code Pageable} argument controls the page size (i.e. the cap on how
     * many events are loaded for scoring).
     *
     * @param sessionId the server-address-based session identifier
     * @param page      a {@link Pageable} limiting the result size
     * @return events ordered by {@code createdAt} descending
     */
    List<AgentEvent> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable page);

    /**
     * Fetch all events of a specific type within a session (used for correction
     * retrieval in future sprint items).
     *
     * @param type      one of the {@code TYPE_*} constants on {@link AgentEvent}
     * @param sessionId the server-address-based session identifier
     * @return matching events, unordered
     */
    List<AgentEvent> findByTypeAndSessionId(String type, String sessionId);
}
