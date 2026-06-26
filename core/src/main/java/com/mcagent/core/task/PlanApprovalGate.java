package com.mcagent.core.task;

import com.mcagent.core.config.BotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides whether a {@link Plan} requires player approval before execution.
 *
 * <p>A plan requires approval when any of the following thresholds are exceeded
 * (values are configurable via {@code bot.plan.*} in {@code application.yml}):
 * <ul>
 *   <li>Step count &gt; {@link BotProperties.PlanProperties#getApprovalStepThreshold()}</li>
 *   <li>Total estimated wall-clock &gt; {@link BotProperties.PlanProperties#getApprovalDurationSeconds()} seconds</li>
 * </ul>
 *
 * <p>Build-volume gating (block count &gt; 50) is deferred to Sprint 14 when
 * {@code BuildStep} / {@code PlaceStep} plan types land.</p>
 */
@Component
@RequiredArgsConstructor
public class PlanApprovalGate {

    private final BotProperties botProps;

    /**
     * Evaluate whether the given plan needs player approval before execution starts.
     *
     * @param plan the candidate plan; must not be {@code null}
     * @return {@code true} if the plan should be held as
     *         {@link TaskState#PENDING_APPROVAL} instead of being submitted immediately
     */
    public boolean requiresApproval(Plan plan) {
        BotProperties.PlanProperties props = botProps.getPlan();

        if (plan.steps().size() > props.getApprovalStepThreshold()) {
            return true;
        }

        long totalSeconds = plan.steps().stream()
                .mapToLong(s -> s.timeout() != null ? s.timeout().getSeconds() : 0L)
                .sum();
        if (totalSeconds > props.getApprovalDurationSeconds()) {
            return true;
        }

        // Build-volume check deferred to Sprint 14 (BuildStep / PlaceStep not yet defined).
        return false;
    }
}
