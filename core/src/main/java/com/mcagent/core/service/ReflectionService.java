package com.mcagent.core.service;

import dev.langchain4j.service.SystemMessage;

/**
 * LangChain4j AI-Service that writes a short postmortem after a task ends.
 *
 * <p>Implements the Reflexion pattern (Shinn et al., NeurIPS 2023): after every
 * completed or failed {@link com.mcagent.core.task.Plan}, a single stateless LLM
 * call produces a 2-3 sentence reflection. The reflection is then persisted as an
 * episodic memory event so future planning turns can learn from past attempts.</p>
 *
 * <p>The proxy is generated at runtime by LangChain4j and wired in
 * {@link com.mcagent.core.config.LangChain4jConfig}. It is stateless: no chat
 * memory, no tools — each call is an independent single-turn exchange.</p>
 *
 * <p>If the LLM call fails for any reason, the caller ({@link com.mcagent.core.task.JobSupervisor})
 * catches the exception and continues. Reflection failures must never disrupt the
 * task lifecycle.</p>
 */
public interface ReflectionService {

    @SystemMessage("""
            You are the Reflection role for McAgent, a Minecraft bot assistant.
            You write short postmortems on completed or failed tasks.

            Given a task summary and outcome, write exactly 2-3 sentences answering:
            - What worked?
            - What didn't (if anything)?
            - What would you do differently next time?

            Rules:
            - Be concrete and specific about Minecraft actions and failures.
            - No filler phrases like "Overall, ..." or "In conclusion, ...".
            - No apologies or hedging.
            - Output plain text only. No JSON, no markdown, no bullet points.
            - If the task succeeded cleanly, focus on what technique was effective.
            - If the task failed, focus on the root cause and the corrective action.
            """)
    String reflect(@dev.langchain4j.service.UserMessage String taskContext);
}
