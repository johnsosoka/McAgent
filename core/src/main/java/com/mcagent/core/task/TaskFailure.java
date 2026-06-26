package com.mcagent.core.task;

import java.util.Map;
import java.util.UUID;

/**
 * Captures all relevant context about a failed plan step so the
 * {@link com.mcagent.core.service.PlanFailureExplainer} can reason about it.
 *
 * <p>Built by {@link JobSupervisor} immediately after a step exhausts its
 * retry budget. Passed to
 * {@link com.mcagent.core.service.LangChain4jService#processFailureEscalation}
 * which forwards it to the LLM-backed explainer.</p>
 *
 * @param taskId             The plan's unique identifier.
 * @param planSummary        Short human-readable description of the overall plan.
 * @param stepIndex          Zero-based index of the step that failed.
 * @param totalSteps         Total number of steps in the plan.
 * @param stepDescription    Human-readable step description.
 * @param reason             Structured failure reason from the step executor.
 * @param retriesUsed        Number of retries already consumed for this step.
 * @param worldStateSnapshot Lightweight snapshot of inventory / position at failure time.
 */
public record TaskFailure(
        UUID taskId,
        String planSummary,
        int stepIndex,
        int totalSteps,
        String stepDescription,
        String reason,
        int retriesUsed,
        Map<String, Object> worldStateSnapshot
) {
    /**
     * Produce a structured text block that the explainer's system prompt can reason about.
     *
     * <p>This format is intentionally prose-like so the LLM can extract intent,
     * not JSON — the LLM's response is the JSON that matters.</p>
     */
    public String toStructuredPrompt() {
        return "Task: " + planSummary + " (id=" + taskId + ")" +
               "\nFailed step " + (stepIndex + 1) + "/" + totalSteps + ": " + stepDescription +
               "\nFailure reason: " + reason +
               "\nRetries already used: " + retriesUsed +
               "\nWorld state at failure: " + formatWorldState();
    }

    private String formatWorldState() {
        if (worldStateSnapshot == null || worldStateSnapshot.isEmpty()) {
            return "(unavailable)";
        }
        StringBuilder sb = new StringBuilder();
        worldStateSnapshot.forEach((k, v) -> sb.append(k).append('=').append(v).append(", "));
        // Remove trailing ", "
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }
}
