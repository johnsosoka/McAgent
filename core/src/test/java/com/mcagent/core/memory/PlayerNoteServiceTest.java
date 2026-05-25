package com.mcagent.core.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(PlayerNoteService.class)
class PlayerNoteServiceTest {

    @Autowired
    private PlayerNoteService service;

    @Autowired
    private PlayerNoteRepository repository;

    @Test
    void shouldSaveNote() {
        PlayerNote saved = service.save("Alice", "Bring more wood", "todo");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPlayerName()).isEqualTo("Alice");
        assertThat(saved.getContent()).isEqualTo("Bring more wood");
    }

    @Test
    void shouldFindByPlayerNameCaseInsensitive() {
        service.save("Alice", "Note 1", "tag1");
        service.save("alice", "Note 2", "tag2");
        service.save("Bob", "Bob's note", "tag3");

        List<PlayerNote> notes = service.findByPlayer("Alice");
        assertThat(notes).hasSize(2);
        assertThat(notes.get(0).getContent()).isEqualTo("Note 2"); // Most recent first
    }

    @Test
    void shouldSearchNotesByContentOrTags() {
        service.save("Alice", "Need diamonds for armor", "resources");
        service.save("Alice", "Build a new farm", "building");
        service.save("Bob", "Collect seeds", "farming");

        List<PlayerNote> results = service.search("Alice", "diamonds");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).contains("diamonds");
    }

    @Test
    void shouldSearchNotesByTag() {
        service.save("Alice", "Content A", "urgent");
        service.save("Alice", "Content B", "later");

        List<PlayerNote> results = service.search("Alice", "urgent");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTags()).isEqualTo("urgent");
    }

    @Test
    void shouldDeleteNote() {
        PlayerNote note = service.save("Alice", "To be deleted", "temp");
        UUID id = note.getId();

        service.delete(id);

        assertThat(repository.findById(id)).isEmpty();
    }
}
