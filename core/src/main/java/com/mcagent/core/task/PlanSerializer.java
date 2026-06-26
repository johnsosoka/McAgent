package com.mcagent.core.task;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Serializes and deserializes {@link Plan} objects to/from JSON for {@link SessionState} storage.
 *
 * <p>A dedicated mapper is used rather than Spring's shared ObjectMapper so that the serialization
 * format for plans is stable and independent of application-level ObjectMapper config changes.</p>
 *
 * <p>{@link Duration} values are stored as ISO-8601 strings (e.g. {@code "PT30M"}).
 * {@link Instant} values are stored as ISO-8601 strings (e.g. {@code "2026-06-25T10:00:00Z"}).
 * This avoids requiring the optional {@code jackson-datatype-jsr310} module.</p>
 *
 * <p>Sealed types ({@link Step}, {@link WaitCondition}) use Jackson's polymorphic
 * {@code @JsonTypeInfo} + {@code @JsonSubTypes} annotations for round-trip safety.</p>
 */
public final class PlanSerializer {

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        SimpleModule javaTimeModule = new SimpleModule("java-time-lite");

        javaTimeModule.addSerializer(Duration.class, new JsonSerializer<>() {
            @Override
            public void serialize(Duration value, JsonGenerator gen, SerializerProvider p)
                    throws IOException {
                gen.writeString(value.toString()); // ISO-8601 e.g. PT30M
            }
        });
        javaTimeModule.addDeserializer(Duration.class, new JsonDeserializer<>() {
            @Override
            public Duration deserialize(JsonParser p, DeserializationContext ctx)
                    throws IOException {
                return Duration.parse(p.getText());
            }
        });

        javaTimeModule.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider p)
                    throws IOException {
                gen.writeString(value.toString()); // ISO-8601
            }
        });
        javaTimeModule.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctx)
                    throws IOException {
                return Instant.parse(p.getText());
            }
        });

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(javaTimeModule);
        return mapper;
    }

    private PlanSerializer() {}

    /**
     * Serialize a {@link Plan} to a JSON string.
     *
     * @param plan the plan to serialize
     * @return JSON string
     * @throws IllegalArgumentException if serialization fails
     */
    public static String toJson(Plan plan) {
        try {
            return MAPPER.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize Plan id=" + plan.id(), e);
        }
    }

    /**
     * Deserialize a {@link Plan} from a JSON string.
     *
     * @param json the JSON string
     * @return the deserialized Plan
     * @throws IllegalArgumentException if deserialization fails
     */
    public static Plan fromJson(String json) {
        try {
            return MAPPER.readValue(json, Plan.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize Plan from JSON", e);
        }
    }

    /**
     * Parse a JSON array string into a list of {@link Step} objects.
     * Used by the {@code submitPlan} tool when the LLM passes raw steps JSON.
     *
     * @param stepsJson JSON array string of step objects
     * @return parsed list of steps
     * @throws IllegalArgumentException if the JSON is invalid or contains unknown step types
     */
    public static List<Step> stepsFromJson(String stepsJson) {
        try {
            return MAPPER.readValue(
                    stepsJson,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Step.class));
        } catch (Exception e) {
            // Provide the raw parsing message so the LLM gets a useful error
            throw new IllegalArgumentException("Invalid steps JSON: " + e.getMessage(), e);
        }
    }
}
