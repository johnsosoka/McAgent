package com.mcagent.core.service;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.model.BotAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates bot actions against safety rules before execution.
 */
@Component
@RequiredArgsConstructor
public class SafetyValidator {

    private final BotProperties properties;

    public ValidationResult validate(BotAction action) {
        return switch (action.getType()) {
            case MINE -> validateMining(action);
            case NAVIGATE, FOLLOW, REMEMBER_LOCATION, RECALL_LOCATION,
                 REMEMBER_NOTE, RECALL_NOTE, QUERY, CANCEL -> ValidationResult.approved();
            default -> ValidationResult.approved();
        };
    }

    private ValidationResult validateMining(BotAction action) {
        // Check for dangerous blocks
        if (action.getTarget() != null) {
            String upper = action.getTarget().toUpperCase();
            for (String dangerous : properties.getSafety().getDangerousBlocks()) {
                if (upper.contains(dangerous)) {
                    return ValidationResult.requiresConfirmation(
                            "This operation involves " + dangerous + ". Confirm to proceed."
                    );
                }
            }
        }
        return ValidationResult.approved();
    }

    public record ValidationResult(boolean isApproved, boolean needsConfirmation, String reason) {
        public static ValidationResult approved() {
            return new ValidationResult(true, false, null);
        }

        public static ValidationResult rejected(String reason) {
            return new ValidationResult(false, false, reason);
        }

        public static ValidationResult requiresConfirmation(String reason) {
            return new ValidationResult(true, true, reason);
        }
    }
}
