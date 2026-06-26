package com.mcagent.core.memory.episodic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility for converting a {@code float[]} embedding vector to and from JSON text
 * for storage in the {@link AgentEvent#getEmbeddingJson()} column.
 */
@Slf4j
final class EmbeddingSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbeddingSerializer() {}

    /**
     * Serialize a float vector to a compact JSON array string.
     *
     * @param vector the embedding produced by the model; must not be null
     * @return JSON string, e.g. {@code [0.1,0.2,0.3]}
     * @throws IllegalArgumentException if serialization fails
     */
    static String toJson(float[] vector) {
        try {
            // Convert to Float[] so Jackson renders floats without scientific notation quirks.
            Float[] boxed = new Float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                boxed[i] = vector[i];
            }
            return MAPPER.writeValueAsString(boxed);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize embedding vector", e);
        }
    }

    /**
     * Deserialize a JSON array string back to a primitive float array.
     *
     * @param json a JSON array string previously produced by {@link #toJson}
     * @return the deserialized float array, or an empty array on parse failure
     */
    static float[] fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new float[0];
        }
        try {
            float[] result = MAPPER.readValue(json, float[].class);
            return result != null ? result : new float[0];
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize embedding JSON — returning empty vector: {}", e.getMessage());
            return new float[0];
        }
    }
}
