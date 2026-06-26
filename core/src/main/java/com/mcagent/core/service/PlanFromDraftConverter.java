package com.mcagent.core.service;

import com.mcagent.core.task.InventoryHasCondition;
import com.mcagent.core.task.MineStep;
import com.mcagent.core.task.NavigateStep;
import com.mcagent.core.task.Plan;
import com.mcagent.core.task.RetryPolicy;
import com.mcagent.core.task.Step;
import com.mcagent.core.task.TerminationPolicy;
import com.mcagent.core.task.WaitCondition;
import com.mcagent.core.task.WaitForStep;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts a {@link PlanDraft} emitted by the {@link Planner} into a fully-typed
 * {@link Plan} suitable for submission to {@link com.mcagent.core.task.TaskExecutor}.
 *
 * <p>The LLM outputs loosely-typed JSON maps. This converter validates each map's
 * structure and builds the appropriate sealed {@link Step} variant. Any structural
 * inconsistency — missing required fields, unrecognised step type, type mismatch —
 * throws {@link PlanValidationException} rather than propagating a raw
 * {@code ClassCastException} or {@code NullPointerException}.</p>
 *
 * <p>This class is intentionally stateless and final — all methods are static.
 * Spring does not manage it; it is called directly from {@link MinecraftTools}.</p>
 */
public final class PlanFromDraftConverter {

    private PlanFromDraftConverter() {}

    // Default timeouts matching the Planner system prompt specification.
    private static final Duration DEFAULT_NAVIGATE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_MINE_TIMEOUT     = Duration.ofSeconds(1800);
    private static final Duration DEFAULT_WAIT_FOR_TIMEOUT = Duration.ofSeconds(600);

    /**
     * Convert a {@link PlanDraft} to a typed {@link Plan}.
     *
     * @param draft       The raw output from the {@link Planner} AI-Service.
     * @param requestedBy The player name to embed in the plan (for traceability).
     * @return A fully-validated, immutable {@link Plan}.
     * @throws PlanValidationException if any field is missing, has the wrong type,
     *                                  or references an unknown step type.
     */
    public static Plan convert(PlanDraft draft, String requestedBy) {
        if (draft == null) {
            throw new PlanValidationException("Planner returned a null draft");
        }
        if (draft.summary() == null || draft.summary().isBlank()) {
            throw new PlanValidationException("Plan draft is missing a summary");
        }
        if (draft.steps() == null || draft.steps().isEmpty()) {
            throw new PlanValidationException("Plan draft contains no steps");
        }

        List<Step> steps = new ArrayList<>(draft.steps().size());
        for (int i = 0; i < draft.steps().size(); i++) {
            Map<String, Object> raw = draft.steps().get(i);
            steps.add(convertStep(raw, i));
        }

        return new Plan(
                UUID.randomUUID(),
                draft.summary(),
                requestedBy,
                Instant.now(),
                steps,
                TerminationPolicy.DEFAULT
        );
    }

    // -------------------------------------------------------------------------
    // Step conversion
    // -------------------------------------------------------------------------

    private static Step convertStep(Map<String, Object> raw, int index) {
        if (raw == null) {
            throw new PlanValidationException("Step[" + index + "] is null");
        }

        String type = requireString(raw, "type", index);
        return switch (type) {
            case "Navigate" -> convertNavigate(raw, index);
            case "Mine"     -> convertMine(raw, index);
            case "WaitFor"  -> convertWaitFor(raw, index);
            default -> throw new PlanValidationException(
                    "Step[" + index + "] has unknown type '" + type + "'. " +
                    "Supported types: Navigate, Mine, WaitFor");
        };
    }

    private static NavigateStep convertNavigate(Map<String, Object> raw, int index) {
        int x              = requireInt(raw, "x", index);
        int y              = requireInt(raw, "y", index);
        int z              = requireInt(raw, "z", index);
        Duration timeout   = timeoutOrDefault(raw, index, DEFAULT_NAVIGATE_TIMEOUT);
        return new NavigateStep(x, y, z, timeout, RetryPolicy.NONE);
    }

    private static MineStep convertMine(Map<String, Object> raw, int index) {
        String blockType   = requireString(raw, "blockType", index);
        int quantity       = requireInt(raw, "quantity", index);
        int searchRadius   = optionalInt(raw, "searchRadius", 64);
        Duration timeout   = timeoutOrDefault(raw, index, DEFAULT_MINE_TIMEOUT);
        return new MineStep(blockType, quantity, searchRadius, timeout, RetryPolicy.NONE);
    }

    private static WaitForStep convertWaitFor(Map<String, Object> raw, int index) {
        Object condObj = raw.get("condition");
        if (condObj == null) {
            throw new PlanValidationException("Step[" + index + "] (WaitFor) is missing 'condition'");
        }
        if (!(condObj instanceof Map<?, ?> condRawUntyped)) {
            throw new PlanValidationException(
                    "Step[" + index + "] (WaitFor) 'condition' must be an object, got: "
                    + condObj.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> condRaw = (Map<String, Object>) condRawUntyped;
        WaitCondition condition = convertCondition(condRaw, index);
        Duration timeout = timeoutOrDefault(raw, index, DEFAULT_WAIT_FOR_TIMEOUT);
        return new WaitForStep(condition, timeout, RetryPolicy.NONE);
    }

    private static WaitCondition convertCondition(Map<String, Object> raw, int stepIndex) {
        // The Planner uses "type" as the discriminator inside the condition object.
        String condType = requireString(raw, "type", stepIndex);
        return switch (condType) {
            case "InventoryHas" -> {
                String itemId = requireString(raw, "itemId", stepIndex);
                int count     = requireInt(raw, "count", stepIndex);
                yield new InventoryHasCondition(itemId, count);
            }
            default -> throw new PlanValidationException(
                    "Step[" + stepIndex + "] condition has unknown type '" + condType + "'. " +
                    "Supported condition types: InventoryHas");
        };
    }

    // -------------------------------------------------------------------------
    // Field extraction helpers
    // -------------------------------------------------------------------------

    private static String requireString(Map<String, Object> map, String field, int stepIndex) {
        Object val = map.get(field);
        if (val == null) {
            throw new PlanValidationException(
                    "Step[" + stepIndex + "] is missing required field '" + field + "'");
        }
        if (!(val instanceof String str)) {
            throw new PlanValidationException(
                    "Step[" + stepIndex + "] field '" + field + "' must be a string, got: "
                    + val.getClass().getSimpleName());
        }
        if (str.isBlank()) {
            throw new PlanValidationException(
                    "Step[" + stepIndex + "] field '" + field + "' must not be blank");
        }
        return str;
    }

    private static int requireInt(Map<String, Object> map, String field, int stepIndex) {
        Object val = map.get(field);
        if (val == null) {
            throw new PlanValidationException(
                    "Step[" + stepIndex + "] is missing required field '" + field + "'");
        }
        if (val instanceof Integer i) {
            return i;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        throw new PlanValidationException(
                "Step[" + stepIndex + "] field '" + field + "' must be an integer, got: "
                + val.getClass().getSimpleName());
    }

    private static int optionalInt(Map<String, Object> map, String field, int defaultValue) {
        Object val = map.get(field);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Integer i) {
            return i;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    /**
     * Extract {@code timeoutSeconds} if present; otherwise return the step-type default.
     * Treats values <= 0 as absent (uses default).
     */
    private static Duration timeoutOrDefault(Map<String, Object> raw, int stepIndex, Duration defaultTimeout) {
        int seconds = optionalInt(raw, "timeoutSeconds", 0);
        return (seconds > 0) ? Duration.ofSeconds(seconds) : defaultTimeout;
    }
}
