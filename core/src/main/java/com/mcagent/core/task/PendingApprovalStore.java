package com.mcagent.core.task;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for a single {@link Plan} that is awaiting player approval.
 *
 * <p>The approval flow:
 * <ol>
 *   <li>{@link PlanApprovalGate} decides a plan is too complex to auto-execute.</li>
 *   <li>The caller stores the plan via {@link #set(Plan)} and presents a preview to the player.</li>
 *   <li>When the player sends {@code agent go} or {@code agent approved},
 *       {@code FabricChatHandler} calls {@link #getAndClear()} to retrieve and remove the plan,
 *       then submits it to the {@link TaskExecutor}.</li>
 *   <li>{@code agent stop} calls {@link #clear()} so stale pending plans do not survive a cancel.</li>
 * </ol>
 *
 * <p>At most one plan may be pending at a time. A subsequent {@link #set(Plan)} call
 * silently replaces any previously stored plan.</p>
 */
@Component
public class PendingApprovalStore {

    private final AtomicReference<Plan> pending = new AtomicReference<>();

    /**
     * Store a plan for approval, replacing any previously pending plan.
     *
     * @param plan the plan to hold pending player approval; must not be {@code null}
     */
    public void set(Plan plan) {
        pending.set(plan);
    }

    /**
     * Atomically retrieve and remove the pending plan.
     *
     * @return the pending plan wrapped in {@link Optional}, or {@link Optional#empty()} if none
     */
    public Optional<Plan> getAndClear() {
        return Optional.ofNullable(pending.getAndSet(null));
    }

    /**
     * Inspect the pending plan without consuming it.
     *
     * @return the pending plan wrapped in {@link Optional}, or {@link Optional#empty()} if none
     */
    public Optional<Plan> peek() {
        return Optional.ofNullable(pending.get());
    }

    /**
     * Discard any pending plan without returning it.
     * Called by the hard-stop bypass so stale plans do not survive a cancel.
     */
    public void clear() {
        pending.set(null);
    }
}
