package com.mcagent.core.service;

import dev.langchain4j.service.SystemMessage;

/**
 * LangChain4j AI-Service that decomposes a natural-language player request into a
 * structured {@link PlanDraft}.
 *
 * <p>This is the Decomposer role from the GITM three-role architecture (prior-art-research §4).
 * The Planner is stateless — it holds no chat memory and calls no tools. Its only job is to
 * translate a request into a typed JSON plan that {@link PlanFromDraftConverter} then validates
 * and that {@link com.mcagent.core.task.TaskExecutor} executes.</p>
 *
 * <p>Sprint 13 v1 uses the same model ({@code ChatModel}) as the {@link Assistant}. Sprint 14
 * P6 will split per-role to route planner calls to a cheaper/faster model endpoint.</p>
 *
 * <p>The proxy is instantiated by LangChain4j at runtime via {@link dev.langchain4j.service.AiServices}.
 * It is wired in {@link com.mcagent.core.config.LangChain4jConfig#planner}.</p>
 */
public interface Planner {

    @SystemMessage("""
            You are the Planner role for McAgent, a Minecraft bot.
            Your sole job: decompose a player request into a typed JSON Plan.

            Output format (strict JSON, no markdown fences):
            {
              "summary": "<brief 1-line description>",
              "steps": [ <list of step objects> ]
            }

            Available step types:
            - {"type":"Navigate","x":INT,"y":INT,"z":INT,"timeoutSeconds":INT}
            - {"type":"Mine","blockType":"minecraft:<id>","quantity":INT,"searchRadius":INT,"timeoutSeconds":INT}
            - {"type":"WaitFor","condition":{"type":"InventoryHas","itemId":"minecraft:<id>","count":INT},"timeoutSeconds":INT}

            Rules:
            - Steps execute in order. Step N+1 starts after step N completes.
            - Use the smallest number of steps that achieves the goal.
            - Single-step tasks (e.g. "come here") should be a single Navigate step.
            - "Mine N iron" is one Mine step, not a Navigate+Mine combo.
            - Time-bounded tasks ("spend 3 hours") use a long timeout on the relevant step.
            - Default timeouts: Navigate=120, Mine=1800, WaitFor=600.
            - Never output any prose, markdown, or commentary. Just the JSON object.
            """)
    PlanDraft propose(@dev.langchain4j.service.UserMessage String playerRequest);
}
