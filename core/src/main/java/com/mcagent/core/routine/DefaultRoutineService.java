package com.mcagent.core.routine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mcagent.core.task.Plan;
import com.mcagent.core.task.PlanSerializer;
import com.mcagent.core.task.RetryPolicy;
import com.mcagent.core.task.Step;
import com.mcagent.core.task.TerminationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link RoutineService}.
 *
 * <p>Substitution uses the Jackson tree model: the plan template JSON is parsed into a
 * {@link JsonNode} tree, then every string node that exactly matches {@code ${paramName}}
 * is replaced with the corresponding argument value. Numeric arguments become numeric
 * JSON nodes; everything else becomes a string node.</p>
 *
 * <p>Substitution is recursive — it walks arrays and objects so placeholders at any
 * nesting depth are resolved. The result is serialized back to JSON and handed to
 * {@link PlanSerializer#stepsFromJson} for final deserialization into typed {@link Step}
 * objects.</p>
 */
@Service
public class DefaultRoutineService implements RoutineService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRoutineService.class);

    /** Matches the full value of a placeholder, e.g. {@code ${baseX}}. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("^\\$\\{([^}]+)}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RoutineRepository repository;

    public DefaultRoutineService(RoutineRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public UUID define(String name, String description, String planTemplate, List<String> parameters) {
        if (repository.existsByName(name)) {
            throw new IllegalArgumentException("Routine already exists: " + name);
        }
        String parametersJson = toParametersJson(parameters);
        RoutineDefinition definition = new RoutineDefinition(
                name, description, planTemplate, parametersJson, false);
        RoutineDefinition saved = repository.save(definition);
        log.info("Defined routine '{}' with {} parameter(s)", name, parameters.size());
        return saved.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Plan invoke(String name, Map<String, Object> args) {
        RoutineDefinition definition = repository.findByName(name)
                .orElseThrow(() -> new RoutineNotFoundException(name));

        try {
            String substituted = substituteTemplate(definition.getPlanTemplate(), args);
            List<Step> steps = PlanSerializer.stepsFromJson(substituted);
            if (steps.isEmpty()) {
                throw new RoutineInvocationException("Routine '" + name + "' resolved to an empty step list");
            }
            return new Plan(
                    UUID.randomUUID(),
                    definition.getDescription() != null ? definition.getDescription() : name,
                    "routine",
                    Instant.now(),
                    steps,
                    TerminationPolicy.DEFAULT);
        } catch (RoutineNotFoundException | RoutineInvocationException e) {
            throw e;
        } catch (Exception e) {
            throw new RoutineInvocationException(
                    "Failed to invoke routine '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoutineDefinition> findByName(String name) {
        return repository.findByName(name);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoutineDefinition> listAll() {
        return repository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    @Override
    @Transactional
    public boolean delete(String name) {
        Optional<RoutineDefinition> found = repository.findByName(name);
        if (found.isEmpty()) {
            return false;
        }
        repository.delete(found.get());
        log.info("Deleted routine '{}'", name);
        return true;
    }

    // -------------------------------------------------------------------------
    // Template substitution
    // -------------------------------------------------------------------------

    /**
     * Replace every {@code ${param}} placeholder string in {@code templateJson}
     * with the corresponding value from {@code args}.
     *
     * <p>Walks the Jackson tree recursively. A string node whose text matches the
     * full pattern {@code ${name}} is replaced with a typed JSON node derived
     * from the argument value:
     * <ul>
     *   <li>{@link Integer} / {@link Long} → integer JSON node</li>
     *   <li>{@link Double} / {@link Float} → double JSON node</li>
     *   <li>Anything else → string JSON node via {@code toString()}</li>
     * </ul>
     * Unknown placeholders (no matching key in {@code args}) are left unchanged so
     * that a missing argument produces an obvious parse error downstream rather than
     * silently using a wrong value.</p>
     *
     * @param templateJson raw JSON string with optional {@code ${…}} placeholders
     * @param args         map of parameter names to substitution values
     * @return JSON string after all resolvable placeholders have been replaced
     * @throws RoutineInvocationException if the template is not valid JSON
     */
    String substituteTemplate(String templateJson, Map<String, Object> args) {
        try {
            JsonNode root = MAPPER.readTree(templateJson);
            JsonNode substituted = substituteNode(root, args);
            return MAPPER.writeValueAsString(substituted);
        } catch (RoutineInvocationException e) {
            throw e;
        } catch (Exception e) {
            throw new RoutineInvocationException(
                    "Plan template is not valid JSON: " + e.getMessage(), e);
        }
    }

    private JsonNode substituteNode(JsonNode node, Map<String, Object> args) {
        if (node.isTextual()) {
            return substituteTextNode(node.asText(), args);
        }
        if (node.isObject()) {
            return substituteObjectNode((ObjectNode) node, args);
        }
        if (node.isArray()) {
            return substituteArrayNode((ArrayNode) node, args);
        }
        // Numbers, booleans, null — return as-is.
        return node;
    }

    private JsonNode substituteTextNode(String text, Map<String, Object> args) {
        var matcher = PLACEHOLDER_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return TextNode.valueOf(text);
        }
        String paramName = matcher.group(1);
        if (!args.containsKey(paramName)) {
            // Leave unresolved placeholder as-is; the downstream parser will report a clear error.
            return TextNode.valueOf(text);
        }
        return valueToJsonNode(args.get(paramName));
    }

    private ObjectNode substituteObjectNode(ObjectNode node, Map<String, Object> args) {
        ObjectNode result = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.set(entry.getKey(), substituteNode(entry.getValue(), args));
        }
        return result;
    }

    private ArrayNode substituteArrayNode(ArrayNode node, Map<String, Object> args) {
        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode element : node) {
            result.add(substituteNode(element, args));
        }
        return result;
    }

    private JsonNode valueToJsonNode(Object value) {
        if (value instanceof Integer i) {
            return MAPPER.getNodeFactory().numberNode(i);
        }
        if (value instanceof Long l) {
            return MAPPER.getNodeFactory().numberNode(l);
        }
        if (value instanceof Double d) {
            return MAPPER.getNodeFactory().numberNode(d);
        }
        if (value instanceof Float f) {
            return MAPPER.getNodeFactory().numberNode(f);
        }
        if (value instanceof Boolean b) {
            return MAPPER.getNodeFactory().booleanNode(b);
        }
        // Strings and anything else become text nodes.
        return MAPPER.getNodeFactory().textNode(value != null ? value.toString() : "");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String toParametersJson(List<String> parameters) {
        try {
            return MAPPER.writeValueAsString(parameters);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize parameters list", e);
        }
    }
}
