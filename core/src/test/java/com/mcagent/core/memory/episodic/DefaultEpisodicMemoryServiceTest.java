package com.mcagent.core.memory.episodic;

import com.mcagent.core.config.BotProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultEpisodicMemoryService}.
 *
 * <p>Uses deterministic mock embeddings to verify the three-factor ranking math,
 * importance scoring, and fallback behaviour — no real LLM or database required.</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultEpisodicMemoryServiceTest {

    @Mock private EmbeddingModel embeddingModel;
    @Mock private ChatModel chatModel;
    @Mock private AgentEventRepository repository;

    private BotProperties botProperties;
    private DefaultEpisodicMemoryService service;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        botProperties.getMemory().setEpisodicEnabled(true);
        botProperties.getMemory().setRetrieveTopK(3);
        botProperties.getMemory().setRecencyDecayHours(8.0);
        botProperties.getMemory().setMaxStoredEventsPerSession(100);

        service = new DefaultEpisodicMemoryService(embeddingModel, chatModel, repository, botProperties);
    }

    // -------------------------------------------------------------------------
    // Retrieval ranking tests
    // -------------------------------------------------------------------------

    @Test
    void retrieve_ranksEventsByThreeFactorScore() {
        // Arrange: three events with different relevance/recency/importance.
        Instant now = Instant.now();

        // Event A: high relevance (cosine ~1.0), old, low importance
        float[] eventAVec = {1.0f, 0.0f};
        AgentEvent eventA = eventWithVec("command", "mine iron", 2, now.minusSeconds(7200), eventAVec);

        // Event B: medium relevance, very recent, high importance
        float[] eventBVec = {0.7f, 0.7f};
        AgentEvent eventB = eventWithVec("plan_complete", "completed mining", 9, now.minusSeconds(60), eventBVec);

        // Event C: low relevance, very old, medium importance
        float[] eventCVec = {0.0f, 1.0f};
        AgentEvent eventC = eventWithVec("observation", "saw a pig", 5, now.minusSeconds(86400), eventCVec);

        when(repository.findBySessionIdOrderByCreatedAtDesc(eq("server-1"), any(Pageable.class)))
                .thenReturn(List.of(eventA, eventB, eventC));

        // Query vector aligned with event A (cosine ~1.0 for A, ~0.7 for B, ~0 for C)
        float[] queryVec = {1.0f, 0.0f};
        stubEmbedding(queryVec);

        // Act
        List<AgentEvent> results = service.retrieve("mine iron ore", "server-1", 3);

        // Assert: all three returned; relative ordering depends on scores
        assertThat(results).hasSize(3);
        // Event B should rank first: high importance (0.9) + very high recency (~1.0) + moderate relevance (~0.7)
        // Event A: relevance 1.0 + recency ~0.77 + importance 0.2 = ~1.97
        // Event B: relevance ~0.7 + recency ~1.0 + importance 0.9 = ~2.6
        // Event C: relevance 0.0 + recency near 0 + importance 0.5 = ~0.5
        assertThat(results.get(0)).isSameAs(eventB);
        assertThat(results.get(1)).isSameAs(eventA);
        assertThat(results.get(2)).isSameAs(eventC);
    }

    @Test
    void retrieve_returnsEmptyList_whenNoEventsStored() {
        when(repository.findBySessionIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        // No embedding needed — the service short-circuits before calling embeddingModel.

        List<AgentEvent> results = service.retrieve("anything", "server-1", 3);

        assertThat(results).isEmpty();
    }

    @Test
    void retrieve_limitsToTopK() {
        Instant now = Instant.now();
        float[] vec = {1.0f, 0.0f};
        List<AgentEvent> five = List.of(
                eventWithVec("command", "a", 5, now, vec),
                eventWithVec("command", "b", 5, now, vec),
                eventWithVec("command", "c", 5, now, vec),
                eventWithVec("command", "d", 5, now, vec),
                eventWithVec("command", "e", 5, now, vec)
        );
        when(repository.findBySessionIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(five);
        stubEmbedding(vec);

        List<AgentEvent> results = service.retrieve("query", "server-1", 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void retrieve_isNoOp_whenEpisodicDisabled() {
        botProperties.getMemory().setEpisodicEnabled(false);

        List<AgentEvent> results = service.retrieve("anything", "server-1", 3);

        assertThat(results).isEmpty();
        verifyNoInteractions(repository, embeddingModel);
    }

    // -------------------------------------------------------------------------
    // Importance scoring tests
    // -------------------------------------------------------------------------

    @Test
    void scoreImportance_parsesIntegerFromLlmResponse() {
        stubImportanceResponse("8");

        int result = service.scoreImportance("player died to creeper");

        assertThat(result).isEqualTo(8);
    }

    @Test
    void scoreImportance_clampsAboveTen() {
        stubImportanceResponse("15");

        int result = service.scoreImportance("something");

        assertThat(result).isEqualTo(10);
    }

    @Test
    void scoreImportance_clampsBelowOne() {
        stubImportanceResponse("0");

        int result = service.scoreImportance("trivial");

        assertThat(result).isEqualTo(1);
    }

    @Test
    void scoreImportance_defaultsToFive_whenLlmReturnsGarbage() {
        stubImportanceResponse("not a number at all!");

        int result = service.scoreImportance("something");

        assertThat(result).isEqualTo(DefaultEpisodicMemoryService.DEFAULT_IMPORTANCE);
    }

    @Test
    void scoreImportance_defaultsToFive_whenLlmThrows() {
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("network error"));

        int result = service.scoreImportance("something");

        assertThat(result).isEqualTo(DefaultEpisodicMemoryService.DEFAULT_IMPORTANCE);
    }

    @Test
    void scoreImportance_handlesResponseWithExtraText() {
        // Some models include surrounding text — should still parse the digit.
        stubImportanceResponse("I'd say: 7 out of 10");

        int result = service.scoreImportance("something");

        assertThat(result).isEqualTo(7);
    }

    // -------------------------------------------------------------------------
    // Write tests
    // -------------------------------------------------------------------------

    @Test
    void write_persistsEventWithCorrectFields() {
        stubEmbedding(new float[]{0.5f, 0.5f});
        stubImportanceResponse("6");

        service.write("command", "mine 64 iron", "server-1", Optional.empty());

        verify(repository).save(argThat(event ->
                "command".equals(event.getType())
                && "mine 64 iron".equals(event.getContent())
                && event.getImportance() == 6
                && "server-1".equals(event.getSessionId())
                && event.getRelatedTaskId() == null
                && event.getEmbeddingJson() != null));
    }

    @Test
    void write_storesRelatedTaskId_whenProvided() {
        stubEmbedding(new float[]{1.0f});
        stubImportanceResponse("5");

        service.write("plan_complete", "done", "server-1", Optional.of("task-uuid-123"));

        verify(repository).save(argThat(event ->
                "task-uuid-123".equals(event.getRelatedTaskId())));
    }

    @Test
    void write_isNoOp_whenEpisodicDisabled() {
        botProperties.getMemory().setEpisodicEnabled(false);

        service.write("command", "mine iron", "server-1", Optional.empty());

        verifyNoInteractions(repository, embeddingModel, chatModel);
    }

    // -------------------------------------------------------------------------
    // Scoring math unit tests
    // -------------------------------------------------------------------------

    @Test
    void cosineSimilarity_returnsOne_forIdenticalVectors() {
        float[] v = {0.6f, 0.8f};
        assertThat(DefaultEpisodicMemoryService.cosineSimilarity(v, v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void cosineSimilarity_returnsZero_forOrthogonalVectors() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertThat(DefaultEpisodicMemoryService.cosineSimilarity(a, b)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void cosineSimilarity_returnsZero_forEmptyVectors() {
        assertThat(DefaultEpisodicMemoryService.cosineSimilarity(new float[0], new float[0])).isEqualTo(0.0);
    }

    @Test
    void recencyScore_isOneForNewEvent() {
        Instant now = Instant.now();
        double score = DefaultEpisodicMemoryService.recencyScore(now, now, 8.0);
        assertThat(score).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void recencyScore_decaysOverTime() {
        Instant now = Instant.now();
        Instant eightHoursAgo = now.minusSeconds(8 * 3600);
        double score = DefaultEpisodicMemoryService.recencyScore(eightHoursAgo, now, 8.0);
        // exp(-1) ≈ 0.368
        assertThat(score).isCloseTo(Math.exp(-1.0), org.assertj.core.data.Offset.offset(0.01));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentEvent eventWithVec(String type, String content, int importance, Instant createdAt, float[] vec) {
        return new AgentEvent(type, content, importance, createdAt,
                "server-1", EmbeddingSerializer.toJson(vec), null);
    }

    private void stubEmbedding(float[] vector) {
        Embedding embedding = Embedding.from(vector);
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(embedding));
    }

    private void stubImportanceResponse(String responseText) {
        // chatModel.chat(String) returns String directly in LangChain4j 1.15+
        when(chatModel.chat(anyString())).thenReturn(responseText);
    }
}
