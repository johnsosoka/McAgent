package com.mcagent.core.service;

import java.util.List;
import java.util.Map;

/**
 * Raw output produced by the {@link Planner} AI-Service before type validation.
 *
 * <p>The LLM emits a JSON object with two fields: {@code summary} (a human-readable
 * description of the plan) and {@code steps} (a list of step maps, each containing
 * at minimum a {@code "type"} key). {@link PlanFromDraftConverter} validates these
 * maps and converts them to strongly-typed {@link com.mcagent.core.task.Step} records.</p>
 *
 * <p>Jackson deserializes the LLM's JSON response into this record via LangChain4j's
 * structured-output mechanism. Fields may be null if the model fails to comply with
 * the schema; {@code PlanFromDraftConverter} treats null inputs as validation errors.</p>
 *
 * @param summary Short description of what the plan accomplishes.
 * @param steps   Raw step descriptors from the LLM; each is a {@code Map<String, Object>}
 *                keyed by field name with values being strings, integers, or nested maps.
 */
public record PlanDraft(
        String summary,
        List<Map<String, Object>> steps
) {}
