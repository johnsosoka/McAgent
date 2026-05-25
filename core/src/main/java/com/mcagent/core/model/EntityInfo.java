package com.mcagent.core.model;

import com.mcagent.core.service.BotOperations;
import lombok.Builder;
import lombok.Data;

/**
 * Information about a non-player entity (mob, animal, etc.) found in the loaded world.
 */
@Data
@Builder
public class EntityInfo {
    private final String type;
    private final BotOperations.Location location;
    private final double distance;
    private final String direction;
}
