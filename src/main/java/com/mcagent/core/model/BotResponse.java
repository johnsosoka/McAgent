package com.mcagent.core.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;

/**
 * Response from the bot after processing a player command.
 */
@Data
@Builder
public class BotResponse {
    private final String message;
    @Singular
    private final List<BotAction> actions;
    private final boolean requiresConfirmation;
    private final Confidence confidence;

    public enum Confidence {
        HIGH,    // >0.9 - Execute immediately
        MEDIUM,  // 0.7-0.9 - Execute with confirmation
        LOW      // <0.7 - Ask for clarification
    }
}
