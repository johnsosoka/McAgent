package com.mcagent.core.service;

import dev.langchain4j.service.SystemMessage;

/**
 * LangChain4j AI-Service that implements the DEPS "Explain" step.
 *
 * <p>When a {@link com.mcagent.core.task.Plan} step fails, {@link JobSupervisorQ1} asks
 * this service to produce a brief natural-language explanation and a recovery decision.
 * The decision is one of three actions:</p>
 * <ul>
 *   <li>{@code RETRY} — transient failure; same step is safe to retry.</li>
 *   <li>{@code REVISE} — plan needs structural change; a replacement plan JSON is
 *       included in the response.</li>
 *   <li>{@code ABANDON} — the goal is infeasible from current world state.</li>
 * </ul>
 *
 * <p>The proxy is generated at runtime by LangChain4j and wired in
 * {@link com.mcagent.core.config.LangChain4jConfig}. It is stateless: no chat
 * memory, no tools — each call is an independent single-turn exchange.</p>
 *
 * <p>Sprint 14 P6 will route this to a dedicated critic model; for Sprint 14 v1 it
 * shares the same {@code ChatModel} as the {@link Assistant}.</p>
 */
public interface PlanFailureExplainer {

    @SystemMessage("""
            You are the Plan Failure Critic for McAgent, a Minecraft bot.
            Given a failed plan step and its failure reason, output a JSON decision.

            Output format (strict JSON, no markdown fences, no prose):
            {
              "explanation": "<1-2 sentence reason why this step failed>",
              "action": "RETRY" | "REVISE" | "ABANDON",
              "revisedPlan": <plan JSON object if action=REVISE, otherwise null>
            }

            Action rules:
            - RETRY: use when the failure was transient (chunk not yet loaded, brief Baritone timeout,
              network blip). The same step is likely to succeed on the next attempt.
            - REVISE: use when the plan needs a structural correction (wrong destination, wrong block
              type, missing precursor step). Include a full revised plan in "revisedPlan".
            - ABANDON: use when the goal is infeasible from the current world state (e.g., no diamonds
              at Y=64, inventory already full with no chest nearby). Explain clearly to the player.

            Additional constraints:
            - Never recommend RETRY more than 2 times for the same step (retries used is provided).
            - If retriesUsed >= 2, choose REVISE or ABANDON instead of RETRY.
            - Revised plans use the same step schema as the original plan:
                Navigate: {"type":"Navigate","x":INT,"y":INT,"z":INT,"timeoutSeconds":INT}
                Mine:     {"type":"Mine","blockType":"minecraft:<id>","quantity":INT,"searchRadius":INT,"timeoutSeconds":INT}
                WaitFor:  {"type":"WaitFor","condition":{"type":"InventoryHas","itemId":"minecraft:<id>","count":INT},"timeoutSeconds":INT}
            - revisedPlan must include "summary" and "steps" keys when action=REVISE.
            - Output strict JSON only. No prose, no markdown, no code fences.
            """)
    FailureDecision explain(@dev.langchain4j.service.UserMessage String failureContext);
}
