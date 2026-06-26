package com.mcagent.core.task.postcondition;

/**
 * Post-condition: the bot's position in the after-snapshot must be within {@code radius}
 * blocks of ({@code targetX}, {@code targetY}, {@code targetZ}).
 */
public record PositionWithin(int targetX, int targetY, int targetZ, int radius) implements PostCondition {

    @Override
    public String describe() {
        return "position within " + radius + " of (" + targetX + "," + targetY + "," + targetZ + ")";
    }
}
