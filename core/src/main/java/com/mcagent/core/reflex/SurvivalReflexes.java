package com.mcagent.core.reflex;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import com.mcagent.core.telemetry.Counters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic, sub-LLM survival reflex layer.
 *
 * <p>Rules execute in strict priority order — the first rule that fires returns
 * immediately and no lower-priority rules are evaluated. This is an intentional
 * short-circuit design: when the bot is critically hurt the hunger rule is
 * irrelevant, and when fleeing it should not stop to place torches.</p>
 *
 * <p>The reflex layer is <em>silent</em> — it never sends player-visible chat.
 * All actions are below the LLM turn boundary; callers log the returned
 * {@link Action} for observability but do not relay it to chat.</p>
 *
 * <h2>Priority order</h2>
 * <ol>
 *   <li><b>FLEE</b> — HP &lt; critical threshold AND at least one threat nearby.</li>
 *   <li><b>EAT</b>  — Food level below eat threshold AND food is available.</li>
 *   <li><b>PLACE_TORCH</b> — Night AND dark location AND torch in inventory.</li>
 *   <li><b>NONE</b>  — No rule fired.</li>
 * </ol>
 *
 * <p>Sprint 14 will refactor this into a formal Behaviour Tree library.
 * For now, plain priority-ordered if/else is sufficient and intentionally
 * simple to read and test.</p>
 */
@Component
public class SurvivalReflexes {

    private static final Logger log = LoggerFactory.getLogger(SurvivalReflexes.class);

    private static final String TORCH_ITEM_ID = "minecraft:torch";

    /**
     * Outcome of a single {@link #tickReflexes} call.
     */
    public enum Action {
        NONE,
        FLEE,
        /** HP is critical but no threats are nearby (e.g. drowning, fall damage, fire). */
        CRITICAL_NO_THREAT,
        EAT,
        PLACE_TORCH
    }

    /**
     * Composite bot operations interface. The reflex layer needs methods from
     * multiple role interfaces (navigation for fleeFrom, control for setSafetyMode
     * / eat / placeTorch, inventory for hasItem), so we depend on the full
     * composite rather than injecting multiple narrower types.
     */
    private final BotOperations bot;
    private final Counters counters;
    private final BotProperties botProps;

    public SurvivalReflexes(BotOperations bot, Counters counters, BotProperties botProps) {
        this.bot = bot;
        this.counters = counters;
        this.botProps = botProps;
    }

    /**
     * Evaluate reflex rules against {@code snapshot} and execute the first
     * matching action.
     *
     * <p>The caller is responsible for:</p>
     * <ul>
     *   <li>Gating by mode — only call from {@code active} mode.</li>
     *   <li>Gating by {@code botProps.getReflex().isEnabled()}.</li>
     *   <li>Ensuring the cache is warm before building the snapshot.</li>
     * </ul>
     *
     * @param snapshot current world state; must not be null
     * @return the action taken, or {@link Action#NONE} if no rule fired
     */
    public Action tickReflexes(ReflexSnapshot snapshot) {
        BotProperties.ReflexProperties reflex = botProps.getReflex();

        // Rule 1a: Critical HP + threats present — safety mode + flee.
        if (isCriticallyHurt(snapshot, reflex) && !snapshot.threats().isEmpty()) {
            ThreatInfo closest = snapshot.threats().get(0);
            bot.setSafetyMode(true);
            bot.fleeFrom(closest.location(), reflex.getFleeDistance());
            counters.inc("reflex.flee");
            log.debug("Reflex FLEE fired: hp={}/{}, threat={}",
                    snapshot.health().health(), snapshot.health().maxHealth(), closest.type());
            return Action.FLEE;
        }

        // Rule 1b: Critical HP with no nearby threats (drowning, fall, fire, etc.).
        // Enable safety mode so pathing turns conservative; return CRITICAL_NO_THREAT so
        // the outer caller (AutonomousObserver) can publish a framework message without
        // this class having to depend on fabric-mod messaging infrastructure.
        if (isCriticallyHurt(snapshot, reflex) && snapshot.threats().isEmpty()) {
            bot.setSafetyMode(true);
            counters.inc("reflex.critical_no_threat");
            log.debug("Reflex CRITICAL_NO_THREAT fired: hp={}/{}",
                    snapshot.health().health(), snapshot.health().maxHealth());
            return Action.CRITICAL_NO_THREAT;
        }

        // Rule 2: Low hunger + food available — eat.
        if (snapshot.health().foodLevel() < reflex.getEatThreshold()) {
            boolean ate = bot.eat(null);
            if (ate) {
                counters.inc("reflex.eat");
                log.debug("Reflex EAT fired: foodLevel={}", snapshot.health().foodLevel());
                return Action.EAT;
            }
            // No food in inventory — nothing we can do; fall through.
        }

        // Note: Rule 2 fall-through is intentional. If the bot is hungry but has no food,
        // we still want to place torches at night — the two concerns are independent.
        // If you change this, update SurvivalReflexesTest accordingly.

        // Rule 3: Night + dark + has torch — place torch.
        if (snapshot.isNight()
                && snapshot.lightLevel() < reflex.getDarkLightThreshold()
                && bot.hasItem(TORCH_ITEM_ID, 1)) {
            bot.placeTorch();
            counters.inc("reflex.torch");
            log.debug("Reflex PLACE_TORCH fired: lightLevel={}", snapshot.lightLevel());
            return Action.PLACE_TORCH;
        }

        return Action.NONE;
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private boolean isCriticallyHurt(ReflexSnapshot snapshot, BotProperties.ReflexProperties reflex) {
        float maxHealth = snapshot.health().maxHealth();
        if (maxHealth <= 0) {
            return false;
        }
        float hpFraction = snapshot.health().health() / maxHealth;
        float criticalFraction = reflex.getHealthCriticalPct() / 100.0f;
        return hpFraction < criticalFraction;
    }
}
