package com.mcagent.core.memory.episodic;

import java.util.List;
import java.util.Optional;

/**
 * Episodic memory service for the McAgent bot.
 *
 * <p>Implements the Generative-Agents-style memory stream: events are written
 * at action time and retrieved later by combining relevance, recency, and
 * importance scores. See {@link DefaultEpisodicMemoryService} for the
 * scoring implementation.</p>
 */
public interface EpisodicMemoryService {

    /**
     * Persist a new event, embedding it and scoring its importance via LLM.
     *
     * @param type          one of the {@code TYPE_*} constants on {@link AgentEvent}
     * @param content       free-text description of what happened
     * @param sessionId     server-address key for session scoping
     * @param relatedTaskId optional UUID string linking this event to an active plan
     */
    void write(String type, String content, String sessionId, Optional<String> relatedTaskId);

    /**
     * Retrieve the top-{@code k} events most relevant to the given query context.
     *
     * <p>Events are scored as:
     * {@code score = relevance(cosine) + recency(exp-decay) + importance/10.0}
     * and returned in descending score order.</p>
     *
     * @param queryContext  the player message or situation text to match against
     * @param sessionId     only events from this session are considered
     * @param topK          maximum number of events to return
     * @return up to {@code topK} events, best match first; empty list if none stored
     */
    List<AgentEvent> retrieve(String queryContext, String sessionId, int topK);
}
