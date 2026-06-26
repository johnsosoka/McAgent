package com.mcagent.core.memory.episodic;

import com.mcagent.core.config.BotProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Generative-Agents-style episodic memory service.
 *
 * <h3>Write path</h3>
 * <ol>
 *   <li>Embed the event content via {@code embeddingModel}.</li>
 *   <li>Ask the {@code chatModel} to score importance (1–10) via a single-line prompt.</li>
 *   <li>Persist the resulting {@link AgentEvent} to the database.</li>
 * </ol>
 *
 * <h3>Retrieve path (three-factor scoring)</h3>
 * <ol>
 *   <li>Embed the query string.</li>
 *   <li>Load the most recent {@code N} events for the session.</li>
 *   <li>For each event compute: {@code score = relevance + recency + importance/10.0}<br>
 *       where {@code relevance = cosine(queryVec, eventVec)},
 *       {@code recency = exp(-ageHours / decayHours)},
 *       and {@code importance = storedValue / 10.0}.</li>
 *   <li>Sort descending, return the top-K.</li>
 * </ol>
 *
 * <p>Reference: Park et al., "Generative Agents: Interactive Simulacra of Human
 * Behavior" (UIST 2023) — §3.2 Memory Retrieval.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultEpisodicMemoryService implements EpisodicMemoryService {

    /**
     * Prompt template for LLM-based importance scoring.
     * The model is asked to reply with ONLY a single integer.
     */
    static final String IMPORTANCE_PROMPT_TEMPLATE =
            "On a scale of 1-10, how important is this event for a Minecraft bot's future planning?\n" +
            "1 = trivial routine. 10 = critical correction or major milestone.\n" +
            "Reply with ONLY the number, nothing else.\n" +
            "Event: ";

    /** Fallback importance when the LLM returns an unparseable response. */
    static final int DEFAULT_IMPORTANCE = 5;

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final AgentEventRepository repository;
    private final BotProperties botProps;

    // -------------------------------------------------------------------------
    // EpisodicMemoryService implementation
    // -------------------------------------------------------------------------

    @Override
    public void write(String type, String content, String sessionId, Optional<String> relatedTaskId) {
        if (!botProps.getMemory().isEpisodicEnabled()) {
            log.debug("Episodic memory disabled — skipping write for type={}", type);
            return;
        }

        try {
            float[] vector = embed(content);
            int importance = scoreImportance(content);
            String embeddingJson = EmbeddingSerializer.toJson(vector);

            AgentEvent event = new AgentEvent(
                    type, content, importance, Instant.now(),
                    sessionId, embeddingJson,
                    relatedTaskId.orElse(null));

            repository.save(event);
            log.debug("Episodic event written: type={} importance={} session={}", type, importance, sessionId);
        } catch (Exception e) {
            // Memory writes must never crash the calling thread.
            log.warn("Failed to write episodic event type={}: {}", type, e.getMessage());
        }
    }

    @Override
    public List<AgentEvent> retrieve(String queryContext, String sessionId, int topK) {
        if (!botProps.getMemory().isEpisodicEnabled()) {
            return List.of();
        }

        int scanLimit = botProps.getMemory().getMaxStoredEventsPerSession();
        List<AgentEvent> candidates = repository.findBySessionIdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, scanLimit));

        if (candidates.isEmpty()) {
            return List.of();
        }

        float[] queryVec = embed(queryContext);
        double decayHours = botProps.getMemory().getRecencyDecayHours();
        Instant now = Instant.now();

        return candidates.stream()
                .map(event -> new ScoredEvent(event, score(event, queryVec, now, decayHours)))
                .sorted(Comparator.comparingDouble(ScoredEvent::score).reversed())
                .limit(topK)
                .map(ScoredEvent::event)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Scoring helpers
    // -------------------------------------------------------------------------

    /**
     * Three-factor score: relevance + recency + (importance / 10.0).
     * Each factor contributes equally (weight 1.0), following the Generative
     * Agents pattern.
     */
    double score(AgentEvent event, float[] queryVec, Instant now, double decayHours) {
        double relevance = cosineSimilarity(queryVec, EmbeddingSerializer.fromJson(event.getEmbeddingJson()));
        double recency = recencyScore(event.getCreatedAt(), now, decayHours);
        double importance = event.getImportance() / 10.0;
        return relevance + recency + importance;
    }

    /**
     * Cosine similarity between two float vectors.
     * Returns 0.0 if either vector is empty or zero-magnitude.
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double magA = 0.0;
        double magB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            magA += (double) a[i] * a[i];
            magB += (double) b[i] * b[i];
        }
        if (magA == 0.0 || magB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(magA) * Math.sqrt(magB));
    }

    /**
     * Exponential recency decay: {@code exp(-ageHours / decayHours)}.
     * Returns 1.0 for brand-new events, approaching 0.0 as age grows.
     */
    static double recencyScore(Instant createdAt, Instant now, double decayHours) {
        double ageHours = Duration.between(createdAt, now).toMillis() / 3_600_000.0;
        return Math.exp(-ageHours / decayHours);
    }

    // -------------------------------------------------------------------------
    // LLM helpers
    // -------------------------------------------------------------------------

    /**
     * Embed a text string via the configured embedding model.
     * Returns a zero-length array on failure (scoring degrades gracefully).
     */
    private float[] embed(String text) {
        try {
            var response = embeddingModel.embed(text);
            return response.content().vector();
        } catch (Exception e) {
            log.warn("Embedding failed for text (len={}): {}", text.length(), e.getMessage());
            return new float[0];
        }
    }

    /**
     * Ask the chat model to rate the importance of an event on a 1-10 scale.
     * Parses the integer from the response; clamps to [1, 10]; defaults to 5 on error.
     */
    int scoreImportance(String content) {
        try {
            String prompt = IMPORTANCE_PROMPT_TEMPLATE + content;
            String response = chatModel.chat(prompt).trim();
            // Extract the FIRST contiguous run of digits from the response.
            // Using a regex matcher so "7 out of 10" yields "7", not "710".
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(response);
            if (!m.find()) {
                log.debug("Importance scoring returned no digits: '{}' — defaulting to {}", response, DEFAULT_IMPORTANCE);
                return DEFAULT_IMPORTANCE;
            }
            int parsed = Integer.parseInt(m.group());
            int clamped = Math.max(1, Math.min(10, parsed));
            log.debug("Importance scored {} (raw: '{}') for content len={}", clamped, response, content.length());
            return clamped;
        } catch (Exception e) {
            log.warn("Importance scoring failed — defaulting to {}: {}", DEFAULT_IMPORTANCE, e.getMessage());
            return DEFAULT_IMPORTANCE;
        }
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    private record ScoredEvent(AgentEvent event, double score) {}
}
