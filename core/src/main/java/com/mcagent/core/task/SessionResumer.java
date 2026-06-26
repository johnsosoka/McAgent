package com.mcagent.core.task;

import com.mcagent.core.service.LangChain4jService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Checks for a recently interrupted {@link TaskState#RUNNING} plan on startup and
 * resumes it if the server address matches.
 *
 * <p>Called from the fabric mod's {@code initSpringContext()} after the Spring context
 * is fully assembled and the server address is known. The 5-minute window is a
 * practical grace period covering brief disconnects, crashes, and reconnects.</p>
 *
 * <h3>Resume semantics</h3>
 * <p>Resumption re-submits the plan starting from the last persisted step index.
 * Baritone re-paths from the bot's current position — no attempt is made to
 * checkpoint Baritone's internal pathfinder state (per the architectural decision
 * in command-mode-architecture.md §7.4).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionResumer {

    private static final long RESUME_WINDOW_MINUTES = 5;

    private final SessionStateRepository repository;
    private final TaskExecutor taskExecutor;
    private final LangChain4jService langChainService;

    /**
     * Attempt to resume a previously running plan for the given server address.
     *
     * @param serverAddress the current server's address (or "single-player")
     */
    public void tryResume(String serverAddress) {
        Instant cutoff = Instant.now().minusSeconds(RESUME_WINDOW_MINUTES * 60);

        Optional<SessionState> candidate = repository
                .findFirstByStateAndUpdatedAtAfterOrderByUpdatedAtDesc(TaskState.RUNNING, cutoff);

        if (candidate.isEmpty()) {
            log.debug("SessionResumer: no recently running plan found within {} minutes", RESUME_WINDOW_MINUTES);
            return;
        }

        SessionState entity = candidate.get();

        if (!serverAddress.equals(entity.getServerAddress())) {
            log.info("SessionResumer: found running plan but server mismatch (current={}, plan={}). Not resuming.",
                    serverAddress, entity.getServerAddress());
            return;
        }

        Plan plan;
        try {
            plan = PlanSerializer.fromJson(entity.getPlanJson());
        } catch (IllegalArgumentException e) {
            log.error("SessionResumer: could not deserialize plan {} — skipping resume", entity.getTaskId(), e);
            return;
        }

        int resumeStep = entity.getCurrentStepIndex();
        int totalSteps = plan.steps().size();
        log.info("SessionResumer: resuming plan '{}' at step {}/{}", plan.summary(), resumeStep + 1, totalSteps);

        String frameworkMsg = "Resumed task '" + plan.summary() + "' at step " +
                (resumeStep + 1) + "/" + totalSteps + " after disconnect.";
        langChainService.addFrameworkContext(frameworkMsg);

        // Re-submit from the persisted step index by building a sub-plan.
        // We create a new Plan with the same ID but starting from the resume step.
        Plan resumePlan = new Plan(
                plan.id(),
                plan.summary(),
                plan.requestedBy(),
                plan.createdAt(),
                plan.steps().subList(resumeStep, totalSteps),
                plan.termination());

        try {
            taskExecutor.submit(resumePlan);
        } catch (IllegalStateException e) {
            log.warn("SessionResumer: could not resume plan — executor already busy: {}", e.getMessage());
        }
    }
}
