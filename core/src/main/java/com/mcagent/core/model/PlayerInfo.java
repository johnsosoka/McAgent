package com.mcagent.core.model;

import com.mcagent.core.service.BotOperations;
import lombok.Builder;
import lombok.Data;

/**
 * Information about a player entity found in the loaded world.
 */
@Data
@Builder
public class PlayerInfo {
    private final String name;
    private final BotOperations.Location location;
    private final double distance;
    private final String direction;
}
