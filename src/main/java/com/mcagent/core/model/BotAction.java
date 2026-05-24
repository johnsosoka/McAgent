package com.mcagent.core.model;

import lombok.Builder;
import lombok.Data;

/**
 * A concrete action the bot should execute in the Minecraft world.
 */
@Data
@Builder
public class BotAction {
    private final ActionType type;
    private final String target;
    private final Integer x;
    private final Integer y;
    private final Integer z;
    private final Integer quantity;
    private final Integer radius;

    public enum ActionType {
        NAVIGATE,
        MINE,
        BUILD,
        FOLLOW,
        REMEMBER_LOCATION,
        RECALL_LOCATION,
        REMEMBER_NOTE,
        RECALL_NOTE,
        QUERY,
        CANCEL
    }
}
