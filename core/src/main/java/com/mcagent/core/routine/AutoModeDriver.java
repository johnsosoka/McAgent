package com.mcagent.core.routine;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.service.BotOperations.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Selects an idle-goal {@link RoutineInvocation} for the bot when running in
 * {@link com.mcagent.core.observer.BotMode#AUTO} mode with no active task.
 *
 * <p>Sprint 14 v1 ships a simple priority list:
 * <ol>
 *   <li>Night → {@code nightShelter} (navigate to base).</li>
 * </ol>
 * Additional heuristics (hunger, full inventory, mining default) are deferred
 * to Sprint 15 when the reflex behaviour tree integrates with this component.</p>
 *
 * <p>Coordinates default to the values configured under {@code bot.routine.baseX/Y/Z}.
 * The driver does not submit the routine — the caller ({@link com.mcagent.fabric.observer.AutonomousObserver})
 * is responsible for calling {@link RoutineService#invoke} and submitting the result
 * to {@link com.mcagent.core.task.TaskExecutor}.</p>
 */
@Component
public class AutoModeDriver {

    private static final Logger log = LoggerFactory.getLogger(AutoModeDriver.class);

    private final BotProperties botProperties;

    public AutoModeDriver(BotProperties botProperties) {
        this.botProperties = botProperties;
    }

    /**
     * Decide which routine to run based on current world state.
     *
     * @param health    Current bot health / hunger snapshot; may be {@code null} if unavailable.
     * @param worldTime Minecraft world time in ticks (0–23999).
     * @param isNight   {@code true} when it is nighttime in the game world.
     * @return An invocation request, or {@link Optional#empty()} when no autonomous action is needed.
     */
    public Optional<RoutineInvocation> pickRoutine(HealthStatus health, int worldTime, boolean isNight) {
        BotProperties.RoutineProperties routineConfig = botProperties.getRoutine();

        if (!routineConfig.isAutoDriverEnabled()) {
            return Optional.empty();
        }

        if (isNight) {
            log.debug("AutoModeDriver: night detected (worldTime={}), selecting nightShelter", worldTime);
            return Optional.of(new RoutineInvocation(
                    "nightShelter",
                    Map.of(
                            "baseX", routineConfig.getBaseX(),
                            "baseY", routineConfig.getBaseY(),
                            "baseZ", routineConfig.getBaseZ())));
        }

        // Sprint 15+ will add: hunger check → eatIfHungry,
        // inventory-full check → depositAndContinue,
        // idle fallback → mineUntilFull of cobblestone.
        return Optional.empty();
    }
}
