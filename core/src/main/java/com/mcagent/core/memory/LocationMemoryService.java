package com.mcagent.core.memory;

import com.mcagent.core.service.BotOperations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing remembered locations.
 * Backed by SQL (primary) with ChromaDB vector search (future enhancement).
 */
@Service
@RequiredArgsConstructor
public class LocationMemoryService {

    private final LocationRepository repository;

    public LocationMemoryEntry save(String name, String description, int x, int y, int z,
                                     LocationType type, String biome, String discoveredBy) {
        LocationMemoryEntry entry = new LocationMemoryEntry(
                name, description, x, y, z, type, biome, discoveredBy
        );
        return repository.save(entry);
    }

    public Optional<LocationMemoryEntry> findByName(String name) {
        return repository.findByNameIgnoreCase(name);
    }

    public List<LocationMemoryEntry> search(String query) {
        return repository.searchByText(query);
    }

    public List<LocationMemoryEntry> findNearby(BotOperations.Location pos, double radius) {
        return repository.findWithinRadius(pos.x(), pos.y(), pos.z(), radius);
    }

    public List<LocationMemoryEntry> listAll() {
        return repository.findAll();
    }

    public void delete(UUID id) {
        repository.deleteById(id);
    }
}
