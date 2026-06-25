package com.mcagent.core.service;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.model.BotAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates bot actions against configured safety rules before execution.
 *
 * <p>Checks:
 * <ul>
 *   <li>Block target not in {@code dangerousBlocks} list (e.g. TNT, LAVA).</li>
 *   <li>Mining Y level does not exceed {@code maxMiningDepth} below the bot's current Y.</li>
 * </ul>
 * </p>
 */
@Component
@RequiredArgsConstructor
public class SafetyValidator {

    private final BotProperties properties;

    /**
     * Validate an action against safety rules.
     *
     * @param action   the action to validate
     * @param currentY the bot's current Y coordinate (used for depth-limit checks)
     * @return {@link ValidationResult} indicating whether the action is allowed
     */
    public ValidationResult validate(BotAction action, int currentY) {
        return switch (action.getType()) {
            case MINE -> validateMine(action, currentY);
            case BUILD -> validateBuild(action);
            case NAVIGATE, FOLLOW, REMEMBER_LOCATION, RECALL_LOCATION,
                 REMEMBER_NOTE, RECALL_NOTE, QUERY, CANCEL -> ValidationResult.approved();
            default -> ValidationResult.approved();
        };
    }

    /**
     * Validates a MINE action against the dangerous-block list and depth limit.
     *
     * <p>Note: target-Y depth enforcement is deferred until tools expose a target-Y parameter.
     * Currently, if {@code action.getY()} is null/zero (no target Y set), the depth check is
     * skipped and only the dangerous-block check runs.</p>
     */
    private ValidationResult validateMine(BotAction action, int currentY) {
        if (action.getTarget() != null) {
            String upper = action.getTarget().toUpperCase();
            for (String dangerous : properties.getSafety().getDangerousBlocks()) {
                if (upper.contains(dangerous.toUpperCase())) {
                    return ValidationResult.rejected(
                            "Cannot interact with " + dangerous + " — listed as dangerous in safety config."
                    );
                }
            }
        }
        // Only check depth if a meaningful target Y was provided (non-zero means explicitly set).
        if (action.getY() != null && action.getY() != 0) {
            int maxDepth = properties.getSafety().getMaxMiningDepth();
            if (action.getY() < currentY - maxDepth) {
                return ValidationResult.rejected(
                        "Mining depth limit (" + maxDepth + ") exceeded."
                );
            }
        }
        return ValidationResult.approved();
    }

    /**
     * Validates a BUILD action against the dangerous-block list.
     * Rejects placement of any block whose ID contains a configured dangerous keyword (e.g. TNT, LAVA).
     */
    private ValidationResult validateBuild(BotAction action) {
        if (action.getTarget() == null || action.getTarget().isBlank()) {
            return ValidationResult.approved();
        }
        String upper = action.getTarget().toUpperCase();
        for (String dangerous : properties.getSafety().getDangerousBlocks()) {
            if (upper.contains(dangerous.toUpperCase())) {
                return ValidationResult.rejected(
                        "Cannot place " + dangerous + " — listed as dangerous in safety config."
                );
            }
        }
        return ValidationResult.approved();
    }

    /**
     * Result of a safety validation check.
     *
     * @param allowed whether the action is permitted
     * @param reason  human-readable rejection reason; {@code null} when allowed
     */
    public record ValidationResult(boolean allowed, String reason) {

        public static ValidationResult approved() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult rejected(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
