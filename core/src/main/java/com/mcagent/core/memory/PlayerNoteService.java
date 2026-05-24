package com.mcagent.core.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing free-text player notes.
 */
@Service
@RequiredArgsConstructor
public class PlayerNoteService {

    private final PlayerNoteRepository repository;

    public PlayerNote save(String playerName, String content, String tags) {
        PlayerNote note = new PlayerNote(playerName, content, tags);
        return repository.save(note);
    }

    public List<PlayerNote> findByPlayer(String playerName) {
        return repository.findByPlayerNameIgnoreCaseOrderByCreatedAtDesc(playerName);
    }

    public List<PlayerNote> search(String playerName, String query) {
        return repository.searchByPlayerAndText(playerName, query);
    }

    public void delete(UUID id) {
        repository.deleteById(id);
    }
}
