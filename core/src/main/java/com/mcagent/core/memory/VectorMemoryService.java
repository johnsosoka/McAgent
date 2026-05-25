package com.mcagent.core.memory;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that stores and searches {@link LocationMemoryEntry} objects using
 * vector embeddings (ChromaDB or in-memory fallback).
 *
 * <p>When the primary embedding store is unavailable, operations gracefully degrade
 * to an internal in-memory store so that the bot can continue working without
 * ChromaDB.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorMemoryService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    private final InMemoryEmbeddingStore<TextSegment> fallbackStore = new InMemoryEmbeddingStore<>();
    private volatile boolean primaryAvailable = true;

    /**
     * Stores a location in the vector store so it can be found via semantic search.
     *
     * @param entry the location to index (must have a non-null ID)
     */
    public void store(LocationMemoryEntry entry) {
        if (entry == null || entry.getId() == null) {
            log.warn("Cannot index null location or location without ID");
            return;
        }

        String text = buildEmbeddingText(entry);
        Embedding embedding = embeddingModel.embed(text).content();
        TextSegment segment = toSegment(entry, text);

        if (primaryAvailable) {
            try {
                embeddingStore.add(embedding, segment);
                log.debug("Indexed location '{}' in vector store", entry.getName());
                return;
            } catch (Exception e) {
                log.warn("Primary vector store unavailable, activating in-memory fallback", e);
                primaryAvailable = false;
            }
        }

        fallbackStore.add(embedding, segment);
        log.debug("Indexed location '{}' in fallback vector store", entry.getName());
    }

    /**
     * Searches for locations whose description is semantically similar to the query.
     *
     * @param query      the natural-language query
     * @param maxResults maximum number of results to return
     * @return list of matching locations ordered by relevance
     */
    public List<LocationMemoryEntry> searchByDescription(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();

        if (primaryAvailable) {
            try {
                matches.addAll(embeddingStore.search(request).matches());
            } catch (Exception e) {
                log.warn("Primary vector store search failed, using fallback", e);
                primaryAvailable = false;
            }
        }

        if (matches.isEmpty() && !primaryAvailable) {
            matches.addAll(fallbackStore.search(request).matches());
        }

        return matches.stream()
                .map(this::toEntry)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Resets the availability flag for the primary store so that the next
     * operation will retry ChromaDB. Useful for health-check recovers.
     */
    public void retryPrimaryStore() {
        this.primaryAvailable = true;
    }

    /**
     * Returns whether the primary (ChromaDB) store is currently considered available.
     */
    public boolean isPrimaryAvailable() {
        return primaryAvailable;
    }

    private String buildEmbeddingText(LocationMemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Location: ").append(entry.getName());
        if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
            sb.append(". Description: ").append(entry.getDescription());
        }
        if (entry.getLocationType() != null) {
            sb.append(". Type: ")
              .append(entry.getLocationType().name().toLowerCase().replace('_', ' '));
        }
        if (entry.getBiome() != null && !entry.getBiome().isBlank()) {
            sb.append(". Biome: ").append(entry.getBiome());
        }
        return sb.toString();
    }

    private TextSegment toSegment(LocationMemoryEntry entry, String text) {
        Metadata metadata = new Metadata();
        metadata.put("id", entry.getId().toString());
        metadata.put("name", entry.getName());
        metadata.put("x", entry.getX());
        metadata.put("y", entry.getY());
        metadata.put("z", entry.getZ());
        metadata.put("type", entry.getLocationType() != null ? entry.getLocationType().name() : "OTHER");
        metadata.put("biome", entry.getBiome() != null ? entry.getBiome() : "");
        metadata.put("discoveredBy", entry.getDiscoveredBy() != null ? entry.getDiscoveredBy() : "");
        metadata.put("description", entry.getDescription() != null ? entry.getDescription() : "");
        return TextSegment.from(text, metadata);
    }

    private LocationMemoryEntry toEntry(EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null) {
            return null;
        }
        TextSegment segment = match.embedded();
        Metadata metadata = segment.metadata();

        try {
            UUID id = UUID.fromString(metadata.getString("id"));
            String name = metadata.getString("name");
            int x = metadata.getInteger("x");
            int y = metadata.getInteger("y");
            int z = metadata.getInteger("z");
            String typeStr = metadata.getString("type");
            LocationType type = typeStr != null ? LocationType.valueOf(typeStr) : LocationType.OTHER;
            String biome = metadata.getString("biome");
            String discoveredBy = metadata.getString("discoveredBy");
            String description = metadata.getString("description");

            LocationMemoryEntry entry = new LocationMemoryEntry(
                    name, description, x, y, z, type, biome, discoveredBy
            );
            entry.setId(id);
            return entry;
        } catch (Exception e) {
            log.warn("Failed to reconstruct LocationMemoryEntry from vector match metadata", e);
            return null;
        }
    }
}
