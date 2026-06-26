package com.mcagent.core.memory.episodic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AgentEventRepository} using an in-memory H2 database.
 *
 * <p>Verifies session-scoped queries and result ordering so the service layer
 * can trust that the JPA queries behave correctly.</p>
 */
@DataJpaTest
class AgentEventRepositoryTest {

    @Autowired
    private AgentEventRepository repository;

    @Test
    void findBySessionIdOrderByCreatedAtDesc_returnsOnlyMatchingSession() {
        Instant base = Instant.now();
        repository.save(event("command", "mine iron", 5, base.minusSeconds(10), "server-A"));
        repository.save(event("command", "build wall", 5, base.minusSeconds(5), "server-B"));
        repository.save(event("plan_complete", "done", 7, base, "server-A"));

        List<AgentEvent> results = repository.findBySessionIdOrderByCreatedAtDesc(
                "server-A", PageRequest.of(0, 100));

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> "server-A".equals(e.getSessionId()));
    }

    @Test
    void findBySessionIdOrderByCreatedAtDesc_returnsNewestFirst() {
        Instant base = Instant.now();
        AgentEvent older = repository.save(event("command", "old cmd", 3, base.minusSeconds(60), "srv"));
        AgentEvent newer = repository.save(event("command", "new cmd", 3, base, "srv"));

        List<AgentEvent> results = repository.findBySessionIdOrderByCreatedAtDesc(
                "srv", PageRequest.of(0, 10));

        assertThat(results.get(0).getId()).isEqualTo(newer.getId());
        assertThat(results.get(1).getId()).isEqualTo(older.getId());
    }

    @Test
    void findBySessionIdOrderByCreatedAtDesc_respectsPageLimit() {
        Instant base = Instant.now();
        for (int i = 0; i < 5; i++) {
            repository.save(event("command", "cmd " + i, 5, base.minusSeconds(i * 10L), "srv"));
        }

        List<AgentEvent> results = repository.findBySessionIdOrderByCreatedAtDesc(
                "srv", PageRequest.of(0, 3));

        assertThat(results).hasSize(3);
    }

    @Test
    void findByTypeAndSessionId_filtersCorrectly() {
        Instant base = Instant.now();
        repository.save(event("command", "mine iron", 5, base, "srv"));
        repository.save(event("plan_complete", "done", 8, base, "srv"));
        repository.save(event("command", "navigate home", 4, base, "srv"));

        List<AgentEvent> commands = repository.findByTypeAndSessionId("command", "srv");
        List<AgentEvent> completions = repository.findByTypeAndSessionId("plan_complete", "srv");

        assertThat(commands).hasSize(2);
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).getContent()).isEqualTo("done");
    }

    @Test
    void findByTypeAndSessionId_doesNotReturnOtherSessions() {
        Instant base = Instant.now();
        repository.save(event("correction", "stop doing that", 9, base, "server-X"));
        repository.save(event("correction", "also stop this", 9, base, "server-Y"));

        List<AgentEvent> results = repository.findByTypeAndSessionId("correction", "server-X");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("stop doing that");
    }

    @Test
    void save_andRetrieve_persistsAllFields() {
        Instant now = Instant.now();
        AgentEvent saved = repository.save(
                new AgentEvent("observation", "creeper spotted at 10,64,20", 8, now,
                        "server-1", "[0.1,0.2,0.3]", "task-abc"));

        AgentEvent loaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getType()).isEqualTo("observation");
        assertThat(loaded.getContent()).isEqualTo("creeper spotted at 10,64,20");
        assertThat(loaded.getImportance()).isEqualTo(8);
        assertThat(loaded.getSessionId()).isEqualTo("server-1");
        assertThat(loaded.getEmbeddingJson()).isEqualTo("[0.1,0.2,0.3]");
        assertThat(loaded.getRelatedTaskId()).isEqualTo("task-abc");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private AgentEvent event(String type, String content, int importance, Instant createdAt, String sessionId) {
        return new AgentEvent(type, content, importance, createdAt, sessionId, "[]", null);
    }
}
